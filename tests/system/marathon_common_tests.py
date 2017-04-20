""" This is a set of tests which are expected to run on root Marathon and marathon on marathon (MoM).
"""
import common
import os
import pytest
import retrying
import shakedown
import time
import uuid

from common import event_fixture
from common import (app, app_mesos, block_port, cluster_info, ensure_mom, group,
                    health_check, ip_of_mom, ip_other_than_mom, pin_to_host,
                    persistent_volume_app, python_http_app, readiness_and_health_app,
                    restore_iptables, nginx_with_ssl_support, command_health_check, delete_all_apps_wait)
from datetime import timedelta
from dcos import http, marathon, mesos
from shakedown import (dcos_1_8, dcos_1_9, dcos_1_10, dcos_version_less_than, private_agents, required_private_agents,
                       marthon_version_less_than, mom_version_less_than)
from urllib.parse import urljoin
from utils import fixture_dir, get_resource


def test_launch_mesos_container():
    """ Test the successful launch of a mesos container on Marathon.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    client.add_app(app_mesos(app_id))
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    app = client.get_app(app_id)

    assert len(tasks) == 1
    assert app['container']['type'] == 'MESOS'


def test_launch_mesos_container():
    """ Test the successful launch of a mesos container on Marathon.
        This is a UCR test with a standard command.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    client.add_app(app_mesos(app_id))
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    app = client.get_app(app_id)

    assert len(tasks) == 1
    assert app['container']['type'] == 'MESOS'


def test_launch_docker_container():
    """ Test the successful launch of a docker container on Marathon.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    client.add_app(app_docker(app_id))
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    app = client.get_app(app_id)

    assert len(tasks) == 1
    assert app['container']['type'] == 'DOCKER'


def test_launch_mesos_container_with_docker_image():
    """ Test the successful launch of a mesos container (ucr) with a docker image with Marathon.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    app_json = app_ucr(app_id)
    client.add_app(app_json)
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    app = client.get_app(app_id)

    assert len(tasks) == 1
    assert app['container']['type'] == 'MESOS'


# this fails on 1.7, it is likely the version of marathon in universe for 1.7
# which is 1.1.5.   We do not have a check for marathon version.
@dcos_1_8
def test_launch_mesos_graceperiod(marathon_service_name):
    """ Test the 'taskKillGracePeriodSeconds' in a Marathon environment.  Read more details
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

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    client.scale_app(app_id, 0)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    # task should still be here after the default_graceperiod
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    # but not after the set graceperiod
    time.sleep(graceperiod)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is None


def test_launch_docker_graceperiod(marathon_service_name):
    """ Test the 'taskKillGracePeriodSeconds' in a Marathon environment.
        This is the same test as above however tests against docker.
    """

    app_id = uuid.uuid4().hex
    app_def = app_docker(app_id)
    app_def['container']['docker']['image'] = 'kensipe/python-test'
    default_graceperiod = 3
    graceperiod = 20
    app_def['taskKillGracePeriodSeconds'] = graceperiod
    app_def['cmd'] = 'python test.py'

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    client.scale_app(app_id, 0)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    # task should still be here after the default_graceperiod
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is not None

    # but not after the set graceperiod
    time.sleep(graceperiod)
    tasks = shakedown.get_service_task(marathon_service_name, app_id)
    assert tasks is None


def test_docker_port_mappings():
    """ Tests docker ports are mapped and are accessible from the host.
    """
    app_id = uuid.uuid4().hex
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


def test_docker_dns_mapping(marathon_service_name):
    """ Tests that a running docker task is accessible from DNS.
    """

    app_id = uuid.uuid4().hex
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
        cmd = 'ping -c 1 {}.{}.mesos'.format(app_id, marathon_service_name)
        wait_for_dns('{}.{}.mesos'.format(app_id, marathon_service_name))
        status, output = shakedown.run_command_on_master(cmd)
        assert status


def test_launch_app_timed():
    """ Most tests wait until a task is launched with no reference to time.
    This simple test verifies that if a app is launched on marathon that within 3 secs
    it will be a task.
    """
    app_id = uuid.uuid4().hex
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


def test_ui_available(marathon_service_name):
    """ This simply confirms that a URL call the service endpoint is successful if
    marathon is launched.
    """

    response = http.get("{}/ui/".format(
        shakedown.dcos_service_url(marathon_service_name)))
    assert response.status_code == 200


def test_task_failure_recovers():
    """ Tests that if a task is KILLED, it will be relaunched and the taskID is different.
    """
    app_id = uuid.uuid4().hex
    app_def = app(app_id)

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
@private_agents(2)
def test_scale_app_in_group():
    """ Tests the scaling of an individual app in a group
    """
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


@private_agents(2)
def test_scale_app_in_group_then_group():
    """ Tests the scaling of an app in the group, then the group
    """
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


@pytest.mark.parametrize('protocol', ['HTTP', 'MESOS_HTTP', 'TCP', 'MESOS_TCP'])
def test_http_health_check_healthy(protocol):
    """ Test HTTP, MESOS_HTTP, TCP and MESOS_TCP with standard python server
    """
    client = marathon.create_client()
    app_def = python_http_app()
    app_def['id'] = 'no-health'
    client.add_app(app_def)
    shakedown.deployment_wait()

    app = client.get_app('/no-health')

    assert app['tasksRunning'] == 1
    assert app['tasksHealthy'] == 0

    client.remove_app('/no-health')

    assert_app_healthy(client, app_def, health_check(protocol=protocol))


def assert_app_healthy(client, app_def, health_check):
    app_def['id'] = '/healthy'
    app_def['healthChecks'] = [health_check]
    instances = app_def['instances']

    print('Testing {} health check protocol.'.format(health_check['protocol']))
    client.add_app(app_def)
    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())

    app = client.get_app('/healthy')

    assert app['tasksRunning'] == instances
    assert app['tasksHealthy'] == instances
    client.remove_app('/healthy')
    shakedown.deployment_wait()


def test_command_health_check_healthy():
    # Test COMMAND protocol
    client = marathon.create_client()
    app_def = app()

    assert_app_healthy(client, app_def, command_health_check())


# todo need to take a look
@pytest.mark.parametrize('protocol', [
   'MESOS_HTTPS',
   pytest.mark.skipif('mom_version_less_than("1.4.2")')('HTTPS')
])
def test_https_health_check_healthy(protocol):
    """ Test HTTPS and MESOS_HTTPS protocols with a prepared nginx image that enables
        SSL (using self-signed certificate) and listens on 443
    """
    client = marathon.create_client()
    app_def = nginx_with_ssl_support()

    assert_app_healthy(client, app_def, health_check(protocol=protocol, port_index=1))


def test_health_check_unhealthy():
    """ Tests failed health checks of an app launched by marathon.
        This was a health check that never passed.
    """
    client = marathon.create_client()
    app_def = python_http_app()
    health_list = []
    health_list.append(health_check('/bad-url', failures=0, timeout=0))
    app_def['id'] = 'unhealthy'
    app_def['healthChecks'] = health_list

    client.add_app(app_def)

    @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
    def check_failure_message():
        app = client.get_app('/unhealthy')
        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 0
        assert app['tasksUnhealthy'] == 1


@private_agents(2)
def test_health_failed_check():
    """ Tests a health check of an app launched by marathon.
        The health check succeeded, then failed due to a network partition.
    """
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


def test_resident_health():
    """ Marathon bug reported: https://jira.mesosphere.com/browse/MARATHON-7050
        Where resident tasks (common for Persistent Volumes) would fail health checks

    """
    app_def = resident_app()
    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())

    tasks = client.get_tasks('/overlay-resident')
    assert len(tasks) == 1

    client.remove_app(app_def['id'])
    shakedown.deployment_wait()


@private_agents(2)
def test_pinned_task_scales_on_host_only():
    """ Tests that scaling a pinned app scales only on the pinned node.
    """
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

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


@private_agents(2)
def test_pinned_task_recovers_on_host():
    """ Tests that a killed pinned task will recover on the pinned node.
    """

    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

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


@private_agents(2)
def test_pinned_task_does_not_scale_to_unpinned_host():
    """ Tests when a task lands on a pinned node (and barely fits) when asked to
        scale past the resources of that node will not scale.
    """

    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    # only 1 can fit on the node
    app_def['cpus'] = 3.5
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


@private_agents(2)
def test_pinned_task_does_not_find_unknown_host():
    """ Tests that a task pinned to an unknown host will not launch.
        within 10 secs it is still in deployment and 0 tasks are running.
    """

    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, '10.255.255.254')
    # only 1 can fit on the node
    app_def['cpus'] = 3.5
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


@private_agents(2)
def test_marathon_with_master_process_failure(marathon_service_name):
    """ Launches an app from Marathon and restarts the master.
        It is expected that the service endpoint will come back and that the
        task_id is the original task_id
    """

    app_def = app('master-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks('/master-failure')
    original_task_id = tasks[0]['id']
    common.systemctl_master()
    shakedown.wait_for_service_endpoint(marathon_service_name)

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_task_recovery():
        tasks = client.get_tasks('/master-failure')
        tasks[0]['id'] == original_task_id


@private_agents(2)
def test_marathon_when_disconnected_from_zk():
    """ Launch an app from Marathon.  Then knock out access to zk from the MoM.
        Verify the task is still good.
    """
    app_def = app('zk-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks('/zk-failure')
    original_task_id = tasks[0]['id']

    with shakedown.iptable_rules(host):
        block_port(host, 2181)
        #  time of the zk block
        time.sleep(10)

    # after access to zk is restored.
    @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
    def check_task_is_back():
        tasks = client.get_tasks('/zk-failure')
        tasks[0]['id'] == original_task_id


@private_agents(2)
def test_marathon_when_task_agent_bounced():
    """ Launch an app and restart the node the task is on.
    """
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks('/agent-failure')
    original_task_id = tasks[0]['id']
    shakedown.restart_agent(host)

    @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
    def check_task_is_back():
        tasks = client.get_tasks('/agent-failure')
        tasks[0]['id'] == original_task_id


def test_default_user():
    """ Ensures the default user of a task is started as root.  This is the default user.
    """

    # launch unique-sleep
    application_json = get_resource("{}/unique-sleep.json".format(fixture_dir()))
    client = marathon.create_client()
    client.add_app(application_json)
    shakedown.deployment_wait()
    app = client.get_app(application_json['id'])
    assert app['user'] is None

    # wait for deployment to finish
    tasks = client.get_tasks("unique-sleep")
    host = tasks[0]['host']

    assert shakedown.run_command_on_agent(host, "ps aux | grep '[s]leep ' | awk '{if ($1 !=\"root\") exit 1;}'")

    client = marathon.create_client()
    client.remove_app("/unique-sleep")


def test_declined_offer_due_to_resource_role():
    """ Tests that an offer was declined because the role doesn't exist
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = common.pending_deployment_due_to_resource_roles(app_id)

    _test_declined_offer(app_id, app_def, 'UnfulfilledRole')


def test_declined_offer_due_to_cpu_requirements():
    """ Tests that an offer was declined because the number of cpus can't be found in an offer
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = common.pending_deployment_due_to_cpu_requirement(app_id)

    _test_declined_offer(app_id, app_def, 'InsufficientCpus')


def _test_declined_offer(app_id, app_def, reason):
    """ Used to confirm that offers were declined.   The `processedOffersSummary` and these tests
        in general require 1.4+ marathon with the queue end point.
        The retry is the best possible way to "time" the success of the test.
    """

    client = marathon.create_client()
    client.add_app(app_def)

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def verify_declined_offer():
        deployments = client.get_deployments(app_id)
        assert len(deployments) == 1

        offer_summary = client.get_queued_app(app_id)['processedOffersSummary']
        role_summary = declined_offer_by_reason(offer_summary['rejectSummaryLastOffers'], reason)
        last_attempt = declined_offer_by_reason(offer_summary['rejectSummaryLaunchAttempt'], reason)

        assert role_summary['declined'] > 0
        assert role_summary['processed'] > 0
        assert last_attempt['declined'] > 0
        assert last_attempt['processed'] > 0


def declined_offer_by_reason(offers, reason):
    for offer in offers:
        if offer['reason'] == reason:
            del offer['reason']
            return offer

    return None


@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    """ Tests the event channel.  The way events are verified is by streaming the events
        to a test.txt file.   The fixture ensures the file is removed before and after the test.
        events checked are connecting, deploying a good task and killing a task.
    """
    app_def = common.app_mesos()
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_deployment_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'event_stream_attached' in stdout
        assert 'deployment_info' in stdout
        assert 'deployment_step_success' in stdout

    client.remove_app(app_id, True)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_kill_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'Killed' in stdout


def test_private_repository_docker_app():
    # Create and copy docker credentials to all private agents
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']
    agents = shakedown.get_private_agents()

    common.create_docker_credentials_file(username, password)
    common.copy_docker_credentials_file(agents)

    client = marathon.create_client()
    app_def = common.private_docker_container_app()
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_app_tasks_running(client, app_def)


@pytest.mark.skip(reason="Not yet implemented in mesos")
def test_private_repository_mesos_app():
    """ Test private docker registry with mesos containerizer using "credentials" container field.
        Note: Despite of what DC/OS docmentation states this feature is not yet implemented:
        https://issues.apache.org/jira/browse/MESOS-7088
    """

    client = marathon.create_client()
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    principal = os.environ['DOCKER_HUB_USERNAME']
    secret = os.environ['DOCKER_HUB_PASSWORD']

    app_def = common.private_mesos_container_app(principal, secret)
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_app_tasks_running(client, app_def)


def test_ping(marathon_service_name):
    """ Tests the API end point for marathon /ping
        This isn't provided by the client object and will need to create the url to test
    """
    response = common.http_get_marathon_path('ping', marathon_service_name)
    assert response.status_code == 200
    assert response.text == 'pong'


@dcos_1_9
def test_vip_mesos_cmd(marathon_service_name):
    """ Tests the creation of a VIP from a python command NOT in a docker.  the
        test validates the creation of an app with the VIP label and the accessability
        of the service via the VIP.
    """
    vip_name = 'vip-service'
    fqn = '{}.{}.l4lb.thisdcos.directory'.format(vip_name, marathon_service_name)
    app_def = python_http_app()
    app_def['portDefinitions'] = [
        {
          "port": 0,
          "protocol": "tcp",
          "name": "{}".format(vip_name),
          "labels": {
            "VIP_0": "/{}:10000".format(vip_name)
          }
        }
        ]
    app_def['id'] = vip_name
    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_http_code('{}:{}'.format(fqn, 10000))


@dcos_1_9
def test_vip_docker_bridge_mode(marathon_service_name):
    """ Tests the creation of a VIP from a python command in a docker image using bridge mode.
        the test validates the creation of an app with the VIP label and the accessability
        of the service via the VIP.
    """
    vip_name = 'vip-docker-service'
    fqn = '{}.{}.l4lb.thisdcos.directory'.format(vip_name, marathon_service_name)
    app_def = app_docker()
    app_def['container']['docker']['portMappings'] = [
        {
          "containerPort": 8080,
          "hostPort": 0,
          "labels": {
            "VIP_0": "/{}:10000".format(vip_name)
          },
          "protocol": "tcp",
          "name": "{}".format(vip_name)
        }
      ]
    app_def['id'] = vip_name
    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    common.assert_http_code('{}:{}'.format(fqn, 10000))


def get_container_pinger_app(name='pinger'):
    return add_container_network(common.pinger_localhost_app(name), 'dcos')


def add_container_network(app_def, network, port=7777):
    app_def['ipAddress'] = {
        "networkName": network,
        "discovery":
        {
            "ports": [{
                "name": "my-port",
                "number": port,
                "protocol": "tcp"
            }]
        }
    }
    del app_def['portDefinitions']
    del app_def['requirePorts']
    return app_def


@pytest.mark.parametrize("test_type, get_pinger_app, dns_format", [
        ('localhost', common.pinger_localhost_app, '{}.{}.mesos'),
        ('bridge', common.pinger_bridge_app, '{}.{}.mesos'),
        ('container', get_container_pinger_app, '{}.{}.containerip.dcos.thisdcos.directory'),
])
@dcos_1_9
@private_agents(2)
def test_network_pinger(test_type, get_pinger_app, dns_format, marathon_service_name):
    """ This test runs a pinger app and a relay app. It retrieves the python app from the
    master via the new http service (which will be moving into shakedown). Then a curl call
    to the relay will invoke a call to the 2nd pinger app and return back pong to the relay
    then back to curl.

    It tests that 1 task can network communicate to another task on the given network
    It tests inbound and outbound connectivity

    test_type param is not used.  It is passed so that it is clear which parametrized test
    is running or may be failing.
    """
    client = marathon.create_client()
    pinger_app = get_pinger_app('pinger')
    relay_app = get_pinger_app('relay')
    pinger_dns = dns_format.format('pinger', marathon_service_name)
    relay_dns = dns_format.format('relay', marathon_service_name)

    # test pinger app to master
    shakedown.copy_file_to_master(fixture_dir() + "/pinger.py")

    with shakedown.master_http_service():
        # need to add app with http service in place or it will fail to fetch
        client.add_app(pinger_app)
        client.add_app(relay_app)
        shakedown.deployment_wait()
        shakedown.wait_for_dns(relay_dns)

    relay_url = 'http://{}:7777/relay-ping?url={}:7777'.format(
        relay_dns, pinger_dns
    )

    @retrying.retry
    def http_output_check(stop_max_attempt_number=30):
        status, output = shakedown.run_command_on_master('curl {}'.format(relay_url))
        assert status
        assert 'Pong /pinger' in output
        assert 'Relay from /relay' in output

    http_output_check()


def clear_marathon():
    try:
        common.stop_all_deployments()
        common.delete_all_apps_wait()
    except Exception as e:
        print(e)

def app_ucr(app_id=None):
    if app_id is None:
        app_id = uuid.uuid4().hex

    return {
        'id': app_id,
        'cmd': 'python3 -m http.server $PORT0',
        'cpus': 0.5,
        'mem': 32.0,
        'container': {
            'type': 'MESOS',
            'docker': {
                'image': 'python:3.5-alpine',
            }
        }
    }


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
                'image': 'python:3.5-alpine',
                'network': 'BRIDGE',
                'portMappings': [
                    {'containerPort': 8080, 'hostPort': 0}
                ]
            }
        }
    }


def resident_app():
    return {
      "id": "/overlay-resident",
      "instances": 1,
      "cpus": 0.1,
      "mem": 128,
      "disk": 100,
      "gpus": 0,
      "container": {
        "type": "DOCKER",
        "volumes": [
          {
            "containerPath": "data",
            "mode": "RW",
            "persistent": {
              "size": 100,
              "type": "root"
            }
          }
        ],
        "docker": {
          "image": "nginx",
          "network": "USER",
          "privileged": False,
          "forcePullImage": False
        }
      },
      "ipAddress": {
        "networkName": "dcos"
      },
      "residency": {
        "relaunchEscalationTimeoutSeconds": 3600,
        "taskLostBehavior": "WAIT_FOREVER"
      },
      "healthChecks": [
        {
          "gracePeriodSeconds": 240,
          "intervalSeconds": 10,
          "timeoutSeconds": 10,
          "maxConsecutiveFailures": 10,
          "port": 80,
          "path": "/",
          "protocol": "HTTP",
          "ignoreHttp1xx": False
        }
      ]
    }
