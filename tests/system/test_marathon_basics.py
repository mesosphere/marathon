"""Marathon tests on DC/OS for negative conditions"""

import pytest
import time
import uuid

from common import *
from shakedown import *
from utils import *
from dcos import *


def test_launch_mesos_container():
    with marathon_on_marathon():
        client = marathon.create_client()
        app_id = uuid.uuid4().hex
        client.add_app(app_mesos(app_id))
        deployment_wait()

        tasks = client.get_tasks(app_id)
        app = client.get_app(app_id)

        assert len(tasks) == 1
        assert app['container']['type'] == 'MESOS'


def test_launch_docker_container():
    with marathon_on_marathon():
        client = marathon.create_client()
        app_id = uuid.uuid4().hex
        client.add_app(app_docker(app_id))
        deployment_wait()

        tasks = client.get_tasks(app_id)
        app = client.get_app(app_id)

        assert len(tasks) == 1
        assert app['container']['type'] == 'DOCKER'


def test_launch_mesos_mom_graceperiod():
    app_id = uuid.uuid4().hex
    app_def = app_mesos(app_id)

    app_def['taskKillGracePeriodSeconds'] = 20
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()

        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None

        client.scale_app(app_id, 0)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None

        # 3 sec is the default
        # should have task still
        time.sleep(5)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None
        time.sleep(20)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is None


def ignore_launch_mesos_mom_default_graceperiod():

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
        deployment_wait()

        task = get_service_task('marathon-user', app_id)
        assert task is not None
        task_id = task.get('id')
        client.scale_app(app_id, 0)
        task = get_service_task('marathon-user', app_id)
        assert task is not None

        # 3 sec is the default
        # should have task still
        time.sleep(5)
        task = get_service_task('marathon-user', app_id)
        assert task is None


def test_launch_docker_mom_graceperiod():
    app_id = uuid.uuid4().hex
    app_def = app_docker(app_id)
    app_def['container']['docker']['image'] = 'kensipe/python-test'
    app_def['taskKillGracePeriodSeconds'] = 20
    app_def['cmd'] = 'python test.py'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()

        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None

        client.scale_app(app_id, 0)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None

        # 3 sec is the default
        # should have task still
        time.sleep(5)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is not None
        time.sleep(20)
        tasks = get_service_task('marathon-user', app_id)
        assert tasks is None


def test_docker_port_mappings():
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_docker(app_id))
        deployment_wait()

        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']
        port = tasks[0]['ports'][0]
        cmd = r'curl -s -w "%{http_code}"'
        cmd = cmd + ' {}:{}/.dockerenv'.format(host, port)
        status, output = run_command_on_agent(host, cmd)

        assert status
        assert output == "200"


def test_docker_dns_mapping():
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        app_json = app_docker(app_id)
        client.add_app(app_json)
        deployment_wait()

        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']

        time.sleep(8)
        bad_cmd = 'ping -c 1 docker-test.marathon-user.mesos-bad'
        cmd = 'ping -c 1 {}.marathon-user.mesos'.format(app_id)
        status, output = run_command_on_master(bad_cmd)
        assert not status

        wait_for_dns('{}.marathon-user.mesos'.format(app_id))
        time.sleep(10)
        status, output = run_command_on_master(cmd)
        assert status


def test_launch_app_timed():
    app_id = uuid.uuid4().hex
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_mesos(app_id))
        # if not launched in 3 sec fail
        time.sleep(3)
        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1


def test_ui_registration_requirement():
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
    response = http.get("{}/ui/".format(dcos_service_url('marathon-user')))
    assert response.status_code == 200


def test_task_failure_recovers():
    app_id = uuid.uuid4().hex
    app_def = app(app_id)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks(app_id)
        host = tasks[0]['host']
        kill_process_on_host(host, '[s]leep')
        deployment_wait()
        time.sleep(5)
        new_tasks = client.get_tasks(app_id)

        assert tasks[0]['id'] != new_tasks[0]['id']


def test_good_user():
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    app_def['user'] = 'core'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks(app_id)
        deployment_wait()
        time.sleep(1)

        assert tasks[0]['id'] != app_def['id']


def test_bad_user():
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    app_def['user'] = 'bad'

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        time.sleep(2)

        appl = client.get_app(app_id)
        message = appl['lastTaskFailure']['message']
        error = "Failed to get user information for 'bad'"
        assert error in message


def test_bad_uri():
    app_id = uuid.uuid4().hex
    app_def = app(app_id)
    fetch = [{
      "uri": "http://mesosphere.io/missing-artifact"
    }]

    app_def['fetch'] = fetch

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        # can't deployment_wait
        # need time to fail at least once
        time.sleep(8)

        appl = client.get_app(app_id)
        message = appl['lastTaskFailure']['message']
        error = "Failed to fetch all URIs for container"
        assert error in message


def test_launch_group():
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/')
            deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2


def test_scale_group():
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        client.scale_group('/test-group/sleep', 2)
        deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 2


def test_scale_app_in_group():
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        client.scale_app('/test-group/sleep/goodnight', 2)
        deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 1


def test_scale_app_in_group_then_group():
    with marathon_on_marathon():
        client = marathon.create_client()
        try:
            client.remove_group('/test-group', True)
            deployment_wait()
        except Exception as e:
            pass

        client.create_group(group())
        deployment_wait()

        group_apps = client.get_group('/test-group/sleep')
        apps = group_apps['apps']
        assert len(apps) == 2
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 1
        assert len(tasks2) == 1

        client.scale_app('/test-group/sleep/goodnight', 2)
        deployment_wait()
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 2
        assert len(tasks2) == 1

        client.scale_group('/test-group/sleep', 2)
        deployment_wait()
        time.sleep(1)
        tasks1 = client.get_tasks('/test-group/sleep/goodnight')
        tasks2 = client.get_tasks('/test-group/sleep/goodnight2')
        assert len(tasks1) == 4
        assert len(tasks2) == 2


def test_health_check_healthy():
    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        app_def['id'] = 'no-health'
        client.add_app(app_def)
        deployment_wait()

        app = client.get_app('/no-health')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 0

        client.remove_app('/no-health')
        health_list = []
        health_list.append(health_check())
        app_def['id'] = 'healthy'
        app_def['healthChecks'] = health_list

        client.add_app(app_def)
        deployment_wait()

        app = client.get_app('/healthy')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 1


def test_health_check_unhealthy():
    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        health_list = []
        health_list.append(health_check('/bad-url', 0, 0))
        app_def['id'] = 'unhealthy'
        app_def['healthChecks'] = health_list

        client.add_app(app_def)
        try:
            deployment_wait(10)
        except Exception as e:
            pass

        app = client.get_app('/unhealthy')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 0
        assert app['tasksUnhealthy'] == 1


def test_health_failed_check():
    agents = get_private_agents()
    if len(agents) < 2:
        raise DCOSException("At least 2 agents required for this test")

    with marathon_on_marathon():
        client = marathon.create_client()
        app_def = python_http_app()
        health_list = []
        health_list.append(health_check())
        app_def['id'] = 'healthy'
        app_def['healthChecks'] = health_list

        pin_to_host(app_def, ip_other_than_mom())

        print(app_def)
        client.add_app(app_def)
        deployment_wait()

        app = client.get_app('/healthy')

        assert app['tasksRunning'] == 1
        assert app['tasksHealthy'] == 1

        tasks = client.get_tasks('/healthy')
        host = tasks[0]['host']
        port = tasks[0]['ports'][0]

        # prefer to break at the agent (having issues)
        mom_ip = ip_of_mom()
        save_iptables(host)
        block_port(host, port)
        time.sleep(7)
        restore_iptables(host)
        deployment_wait()

        new_tasks = client.get_tasks('/healthy')
        print(new_tasks)
        assert new_tasks[0]['id'] != tasks[0]['id']


def test_pinned_task_scales_on_host_only():
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()

        tasks = client.get_tasks('/pinned')
        assert len(tasks) == 1
        assert tasks[0]['host'] == host

        client.scale_app('pinned', 10)
        deployment_wait()

        tasks = client.get_tasks('/pinned')
        assert len(tasks) == 10
        for task in tasks:
            assert task['host'] == host


def test_pinned_task_recovers_on_host():
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/pinned')

        kill_process_on_host(host, '[s]leep')
        deployment_wait()
        time.sleep(3)
        new_tasks = client.get_tasks('/pinned')

        assert tasks[0]['id'] != new_tasks[0]['id']
        assert new_tasks[0]['host'] == host


def test_pinned_task_does_not_scale_to_unpinned_host():
    app_def = app('pinned')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    # only 1 can fit on the node
    app_def['cpus'] = 3.5
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/pinned')
        client.scale_app('pinned', 2)
        # typical deployments are sub 3 secs
        time.sleep(5)
        deployments = client.get_deployments()
        tasks = client.get_tasks('/pinned')

        assert len(deployments) == 1
        assert len(tasks) == 1


def test_pinned_task_does_not_find_unknown_host():
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

def test_launch_container_with_presistent_volume():

    with marathon_on_marathon():
        app_def = peristent_volume_app()
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        cmd = "curl {}:{}/data/foo".format(host, port)
        run, data = run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == 'hello\n', "'{}' was not equal to hello\\n".format(data)

        client.restart_app(app_id)
        deployment_wait()

        tasks = client.get_tasks(app_id)
        assert len(tasks) == 1

        port = tasks[0]['ports'][0]
        host = tasks[0]['host']
        cmd = "curl {}:{}/data/foo".format(host, port)
        run, data = run_command_on_master(cmd)

        assert run, "{} did not succeed".format(cmd)
        assert data == 'hello\nhello\n', "'{}' was not equal to hello\\nhello\\n".format(data)


def setup_function(function):
    with marathon_on_marathon():
        try:
            client = marathon.create_client()
            client.remove_group("/", True)
            deployment_wait()
        except:
            pass


def setup_module(module):
    ensure_mom()
    cluster_info()


def teardown_module(module):
    with marathon_on_marathon():
        client = marathon.create_client()
        client.remove_group("/", True)
        deployment_wait()


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
