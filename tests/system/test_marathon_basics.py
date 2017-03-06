""" Marathon tests on DC/OS for positive and negative conditions.  These tests are run
    againts Marathon on Marathon (MoM).
    `marathon_on_marathon` is a context_manager which switches marathon calls to
    the MoM.
"""

import pytest
import time
import uuid
import retrying
import shakedown

from common import (app, app_mesos, block_port, cluster_info, ensure_mom, group,
                    health_check, ip_of_mom, ip_other_than_mom, pin_to_host,
                    persistent_volume_app, python_http_app, readiness_and_health_app,
                    restore_iptables)
from shakedown import dcos_1_8, dcos_version_less_than, private_agent_2, required_private_agents
from utils import marathon_on_marathon
from dcos import http, marathon, mesos


def test_launch_mesos_container():
    """ Test the successful launch of a mesos container on MoM.
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        app_id = uuid.uuid4().hex
        client.add_app(app_mesos(app_id))
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        app = client.get_app(app_id)

        assert len(tasks) == 1
        assert app['container']['type'] == 'MESOS'


def test_launch_docker_container():
    """ Test the successful launch of a docker container on MoM.
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        app_id = uuid.uuid4().hex
        client.add_app(app_docker(app_id))
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        app = client.get_app(app_id)

        assert len(tasks) == 1
        assert app['container']['type'] == 'DOCKER'


# this fails on 1.7, it is likely the version of marathon in universe for 1.7
# which is 1.1.5.   We do not have a check for mom version.
@dcos_1_8
def test_launch_mesos_mom_graceperiod():
    """ Test the 'taskKillGracePeriodSeconds' in a MoM environment.  Read more details
        on this test in `test_root_marathon.py::test_launch_mesos_root_marathon_graceperiod`
    """

    app_id = uuid.uuid4().hex
    app_def = app_mesos(app_id)
    default_graceperiod = 3
    graceperiod = 20

    app_def['taskKillGracePeriodSeconds'] = graceperiod
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        client.scale_app(app_id, 0)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        # task should still be here after the default_graceperiod
        time.sleep(default_graceperiod + 1)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        # but not after the set graceperiod
        time.sleep(graceperiod)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is None


def ignore_launch_mesos_mom_default_graceperiod():
    """ Test the 'taskKillGracePeriodSeconds' in a MoM environment.  Read more details
        on this test in `test_root_marathon.py::test_launch_mesos_root_marathon_default_graceperiod`
    """

    app_id = uuid.uuid4().hex
    app_def = app_mesos(app_id)

    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        task = shakedown.get_service_task('marathon-user', app_id)
        assert task is not None
        task_id = task.get('id')
        client.scale_app(app_id, 0)
        task = shakedown.get_service_task('marathon-user', app_id)
        assert task is not None

        # 3 sec is the default
        # task should be gone after 3 secs
        default_graceperiod = 3
        time.sleep(default_graceperiod + 1)
        task = shakedown.get_service_task('marathon-user', app_id)
        assert task is None


def test_launch_docker_mom_graceperiod():
    """ Test the 'taskKillGracePeriodSeconds' in a MoM environment.
        This is the same test as above however tests against docker.
    """

    app_id = uuid.uuid4().hex
    app_def = app_docker(app_id)
    app_def['container']['docker']['image'] = 'kensipe/python-test'
    default_graceperiod = 3
    graceperiod = 20
    app_def['taskKillGracePeriodSeconds'] = graceperiod
    app_def['cmd'] = 'python test.py'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        client.scale_app(app_id, 0)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        # task should still be here after the default_graceperiod
        time.sleep(default_graceperiod + 1)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is not None

        # but not after the set graceperiod
        time.sleep(graceperiod)
        tasks = shakedown.get_service_task('marathon-user', app_id)
        assert tasks is None


def test_docker_port_mappings():
    """ Tests docker ports are mapped and are accessible from the host.
    """
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_docker(app_id))
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']
        port = tasks[0]['ports'][0]
        cmd = r'curl -s -w "%{http_code}"'
        cmd = cmd + ' {}:{}/.dockerenv'.format(host, port)
        status, output = shakedown.run_command_on_agent(host, cmd)

        assert status
        assert output == "200"


def test_docker_dns_mapping():
    """ Tests that a running docker task is accessible from DNS.
    """
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        app_json = app_docker(app_id)
        client.add_app(app_json)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']

        bad_cmd = 'ping -c 1 docker-test.marathon-user.mesos-bad'
        status, output = shakedown.run_command_on_master(bad_cmd)
        assert not status

        @retrying.retry(stop_max_delay=10000)
        def check_dns():
            cmd = 'ping -c 1 {}.marathon-user.mesos'.format(app_id)
            wait_for_dns('{}.marathon-user.mesos'.format(app_id))
            status, output = shakedown.run_command_on_master(cmd)
            assert status


def test_launch_app_timed():
    """ Most tests wait until a task is launched with no reference to time.
    This simple test verifies that if a app is launched on marathon that within 3 secs
    it will be a task.
    """
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_mesos(app_id))
        # if not launched in 3 sec fail
        time.sleep(3)
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1


def test_ui_registration_requirement():
    """ Testing the UI is a challenge with this toolchain.  The UI team has the
        best tooling for testing it.   This test verifies that the required configurations
        for the service endpoint and ability to launch to the service UI are present.
    """
    tasks = mesos.get_master().tasks()
    for task in tasks:
        if task['name'] == 'marathon-user':
            for label in task['labels']:
                if label['key'] == 'DCOS_PACKAGE_NAME':
                    assert label['value'] == 'marathon'
                if label['key'] == 'DCOS_PACKAGE_IS_FRAMEWORK':
                    assert label['value'] == 'true'
                if label['key'] == 'DCOS_SERVICE_NAME':
                    assert label['value'] == 'marathon-user'


def test_ui_available():
    """ This simply confirms that a URL call the service endpoint is successful if
    MoM is launched.
    """
    response = http.get("{}/ui/".format(
        shakedown.dcos_service_url('marathon-user')))
    assert response.status_code == 200


def test_task_failure_recovers():
    """ Tests that if a task is KILLED, it will be relaunched and the taskID is different.
    """
    app_id = uuid.uuid4().hex
    app_def = app(app_id)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']
        shakedown.kill_process_on_host(host, '[s]leep')
        shakedown.deployment_wait()

        @retrying.retry(stop_max_delay=10000)
        def check_new_task_id():
            new_tasks = client.get_tasks(app_id)
            assert tasks[0]['id'] != new_tasks[0]['id']


def test_good_user():
    """ Test changes an app from the non-specified (default user) to another
        good user.  This works on coreOS.
    """
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    app_def['user'] = 'core'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        # if bad this wait will fail.
        # Good user `core` didn't launch.  This only works on a coreOS or a system with a core user.
        shakedown.deployment_wait()
        tasks = client.get_tasks(app_id)
        assert tasks[0]['id'] != app_def['id'], "Good user `core` didn't launch.  This only works on a coreOS or a system with a core user."


def test_bad_user():
    """ Test changes the default user to a bad user and confirms that task will
        not launch.
    """
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    app_def['user'] = 'bad'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)

        @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
        def check_failure_message():
            appl = client.get_app(app_id)
            message = appl['lastTaskFailure']['message']
            error = "Failed to get user information for 'bad'"
            assert error in message


def test_bad_uri():
    """ Tests marathon's response to launching a task with a bad url (a url that isn't fetchable)
    """
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    fetch = [{
      "uri": "http://mesosphere.io/missing-artifact"
    }]

    app_def['fetch'] = fetch

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)

        @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
        def check_failure_message():
            appl = client.get_app(app_id)
            message = appl['lastTaskFailure']['message']
            error = "Failed to fetch all URIs for container"
            assert error in message

        check_failure_message()


def test_launch_group():
    """ Tests the lauching a group of apps at the same time (by request, it is 2 deep)
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/')
            shakedown.deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        shakedown.deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2


def test_scale_group():
    """ Tests the scaling of a group
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            shakedown.deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        shakedown.deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        # scale by 2 for the entire group
        client.scale_group('/test-group/sleep', 2)
        shakedown.deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 2


# required_cpus
@private_agent_2
def test_scale_app_in_group():
    """ Tests the scaling of an individual app in a group
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            shakedown.deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        shakedown.deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        # scaling just an app in the group
        client.scale_app('/test-group/sleep/goodnight', 2)
        shakedown.deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 1


@private_agent_2
def test_scale_app_in_group_then_group():
    """ Tests the scaling of an app in the group, then the group
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            shakedown.deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        shakedown.deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        # scaling just an app
        client.scale_app('/test-group/sleep/goodnight', 2)
        shakedown.deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 1

        # scaling the group after 1 app in the group was scaled.
        client.scale_group('/test-group/sleep', 2)
        shakedown.deployment_wait()
        time.sleep(1)
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 4
        assert len(tasks2) == 2


def test_health_check_healthy():
    """ Tests health checks of an app launched by marathon.
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        app_def['id'] = 'no-health'
        client.add_app(app_def)
        shakedown.deployment_wait()

        app = client.get_app('/no-health')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 0

        client.remove_app('/no-health')
        health_list = []
        health_list.append(health_check())
        app_def['id'] = 'healthy'
        app_def['healthChecks'] = health_list

        client.add_app(app_def)
        shakedown.deployment_wait()

        app = client.get_app('/healthy')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 1


def test_health_check_unhealthy():
    """ Tests failed health checks of an app launched by marathon.
        This was a health check that never passed.
    """
    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        health_list = []
        health_list.append(health_check('/bad-url', 0, 0))
        app_def['id'] = 'unhealthy'
        app_def['healthChecks'] = health_list

        client.add_app(app_def)

        @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
        def check_failure_message():
            app = client.get_app('/unhealthy')
            assert app['tasksRunning'] == 1
            assert app['tasksHealthy'] == 0
            assert app['tasksUnhealthy'] == 1


@private_agent_2
def test_health_failed_check():
    """ Tests a health check of an app launched by marathon.
        The health check succeeded, then failed due to a network partition.
    """

    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        health_list = []
        health_list.append(health_check())
        app_def['id'] = 'healthy'
        app_def['healthChecks'] = health_list

        pin_to_host(app_def, ip_other_than_mom())

        client.add_app(app_def)
        shakedown.deployment_wait()

        # healthy
        app = client.get_app('/healthy')
        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 1

        tasks = client.get_tasks('/healthy')
        host = tasks[0]['host']
        port = tasks[0]['ports'][0]

        # prefer to break at the agent (having issues)
        mom_ip = ip_of_mom()
        shakedown.save_iptables(host)
        block_port(host, port)
        time.sleep(7)
        restore_iptables(host)
        shakedown.deployment_wait()

        # after network failure is restored.  The task returns and is a new task ID
        @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
        def check_health_message():
            new_tasks = client.get_tasks('/healthy')
            assert new_tasks[0]['id'] != tasks[0]['id']
            app = client.get_app('/healthy')
            assert app['tasksRunning'] == 1
            assert app['tasksHealthy'] == 1


@private_agent_2
def test_pinned_task_scales_on_host_only():
    """ Tests that scaling a pinned app scales only on the pinned node.
    """
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks('/pinned')
        assert len(tasks) == 1
        assert tasks[0]['host'] == host

        client.scale_app('pinned', 10)
        shakedown.deployment_wait()

        tasks = client.get_tasks('/pinned')
        assert len(tasks) == 10
        for task in tasks:
            assert task['host'] == host


@private_agent_2
def test_pinned_task_recovers_on_host():
    """ Tests that a killed pinned task will recover on the pinned node.
    """
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks('/pinned')

        shakedown.kill_process_on_host(host, '[s]leep')
        shakedown.deployment_wait()

        @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
        def check_for_new_task():
            new_tasks = client.get_tasks('/pinned')
            assert tasks[0]['id'] != new_tasks[0]['id']
            assert new_tasks[0]['host'] == host


@private_agent_2
def test_pinned_task_does_not_scale_to_unpinned_host():
    """ Tests when a task lands on a pinned node (and barely fits) when asked to
        scale past the resources of that node will not scale.
    """
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    # only 1 can fit on the node
    app_def['cpus'] = 3.5
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks('/pinned')
        client.scale_app('pinned', 2)
        # typical deployments are sub 3 secs
        time.sleep(5)
        deployments = client.get_deployments()
        tasks = client.get_tasks('/pinned')

        # still deploying
        assert len(deployments) == 1
        assert len(tasks) == 1


@private_agent_2
def test_pinned_task_does_not_find_unknown_host():
    """ Tests that a task pinned to an unknown host will not launch.
        within 10 secs it is still in deployment and 0 tasks are running.
    """
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, '10.255.255.254')
    # only 1 can fit on the node
    app_def['cpus'] = 3.5
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        # deploys are within secs
        # assuming after 10 no tasks meets criteria
        time.sleep(10)

        tasks = client.get_tasks('/pinned')
        assert len(tasks) == 0


@dcos_1_8
def test_launch_container_with_persistent_volume():
    """ Tests launching a task with PV.  It will write to a file in the PV.
        The app is killed and restarted and we can still read from the PV.
    """
    with marathon_on_marathon():
        app_def = persistent_volume_app()
        app_id = app_def['id']
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        cmd = "curl {}:{}/data/foo".format(host, port)
        run, data = shakedown.run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == 'hello\n', "'{}' was not equal to hello\\n".format(data)

        client.restart_app(app_id)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        cmd = "curl {}:{}/data/foo".format(host, port)
        run, data = shakedown.run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == 'hello\nhello\n', "'{}' was not equal to hello\\nhello\\n".format(data)


def test_update_app():
    """ Tests update an app.
    """
    app_id = uuid.uuid4().hex
    app_def = app_mesos(app_id)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        app_def['cpus'] = 1
        app_def['instances'] = 2
        client.update_app(app_id, app_def)
        shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 2


def test_update_app_rollback():
    """ Tests updating an app then rolling back the update.
    """
    app_id = uuid.uuid4().hex
    app_def = readiness_and_health_app()
    app_def['id'] = app_id

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        # start with 1
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        app_def['instances'] = 2
        client.update_app(app_id, app_def)
        shakedown.deployment_wait()

        # update works to 2
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 2

        # provides a testing delay to rollback from
        app_def['readinessChecks'][0]['intervalSeconds'] = 30
        app_def['instances'] = 1
        deployment_id = client.update_app(app_id, app_def)
        client.rollback_deployment(deployment_id)

        shakedown.deployment_wait()
        # update to 1 instance is rollback to 2
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 2


def test_update_app_poor_health():
    """ Tests updating an app with an automatic rollback due to poor health.
    """
    app_id = uuid.uuid4().hex
    app_def = readiness_and_health_app()
    app_def['id'] = app_id

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        # start with 1
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        # provides a testing delay to rollback from
        app_def['healthChecks'][0]['path'] = '/non-existant'
        app_def['instances'] = 2
        deployment_id = client.update_app(app_id, app_def)
        # 2 min wait
        try:
            shakedown.deployment_wait()
        except:
            client.rollback_deployment(deployment_id)
            shakedown.deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1


def setup_function(function):
    with marathon_on_marathon():
        try:
            client = marathon.create_client()
            client.remove_group("/", True)
            shakedown.deployment_wait()
        except:
            pass


def setup_module(module):
    ensure_mom()
    cluster_info()


def teardown_module(module):
    with marathon_on_marathon():
        client = marathon.create_client()
        client.remove_group("/", True)
        shakedown.deployment_wait()


def app_docker(app_id=None):
    if app_id is None:
        app_id = uuid.uuid4().hex

    return {
        'id': app_id,
        'cmd': 'python3 -m http.server 8080',
        'cpus': 0.5,
        'mem': 32.0,
        'container': {
            'type': 'DOCKER',
            'docker': {
                'image': 'python:3',
                'network': 'BRIDGE',
                'portMappings': [
                    {'containerPort': 8080, 'hostPort': 0}
                ]
            }
        }
    }
