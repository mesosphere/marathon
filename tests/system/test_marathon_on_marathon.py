""" Test using marathon on marathon (MoM).
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to MoM environments only.
"""

import pytest
import common
import shakedown

from datetime import timedelta
# this is intentional import *
# it imports all the common test_ methods which are to be tested on root and mom
from marathon_common_tests import *
from utils import fixture_dir, get_resource

pytestmark = [pytest.mark.usefixtures('marathon_service_name')]


@pytest.fixture(scope="function")
def marathon_service_name():

    shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
    with shakedown.marathon_on_marathon():
        yield 'marathon-user'
        shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
        clear_marathon()


def setup_module(module):
    common.ensure_mom()
    shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
    common.cluster_info()
    with shakedown.marathon_on_marathon():
        clear_marathon()


def teardown_module(module):
    with shakedown.marathon_on_marathon():
        try:
            clear_marathon()
        except:
            pass
    # Uninstall MoM
    shakedown.uninstall_package_and_wait('marathon')
    shakedown.delete_zk_node('universe/marathon-user')
    # Remove everything from root marathon
    clear_marathon()


###########
# MoM only tests
###########


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



@private_agents(2)
def test_mom_when_mom_agent_bounced():
    """ Launch an app from MoM and restart the node MoM is on.
    """
    app_def = app('agent-failure')
    mom_ip = ip_of_mom()
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks('/agent-failure')
        original_task_id = tasks[0]['id']

        shakedown.restart_agent(mom_ip)

        @retrying.retry(wait_fixed=1000, stop_max_delay=3000)
        def check_task_is_back():
            tasks = client.get_tasks('/agent-failure')
            tasks[0]['id'] == original_task_id


@private_agents(2)
def test_mom_when_mom_process_killed():
    """ Launched a task from MoM then killed MoM.
    """
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks('/agent-failure')
        original_task_id = tasks[0]['id']

        shakedown.kill_process_on_host(ip_of_mom(), 'marathon-assembly')
        shakedown.wait_for_task('marathon', 'marathon-user', 300)
        shakedown.wait_for_service_endpoint('marathon-user')

        tasks = client.get_tasks('/agent-failure')
        tasks[0]['id'] == original_task_id


@private_agents(2)
def test_mom_with_network_failure():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures
    simulated by knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: {}".format(mom_ip))

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        original_sleep_task_id = tasks[0]["id"]
        task_ip = tasks[0]['host']

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    time.sleep(timedelta(minutes=1).total_seconds())

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    time.sleep(timedelta(minutes=1).total_seconds())
    shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds())
    shakedown.wait_for_task("marathon-user", "sleep")

    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        shakedown.wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"


@dcos_1_9
@private_agents(2)
def test_mom_with_network_failure_bounce_master():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by
    knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: {}".format(mom_ip))

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        original_sleep_task_id = tasks[0]["id"]
        task_ip = tasks[0]['host']
        print("\nTask IP: " + task_ip)

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    time.sleep(timedelta(minutes=1).total_seconds())

    # bounce master
    shakedown.run_command_on_master("sudo systemctl restart dcos-mesos-master")

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    time.sleep(timedelta(minutes=1).total_seconds())
    shakedown.wait_for_service_endpoint('marathon-user', timedelta(minutes=10).total_seconds())

    with shakedown.marathon_on_marathon():
        client = marathon.create_client()
        shakedown.wait_for_task("marathon-user", "sleep", timedelta(minutes=10).total_seconds())
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"


def test_framework_unavailable_on_mom():
    """ Launches an app that has elements necessary to create a service endpoint in DCOS.
        This test confirms that the endpoint is not created when launched with MoM.
    """
    if shakedown.service_available_predicate('pyfw'):
        client = marathon.create_client()
        client.remove_app('python-http', True)
        shakedown.deployment_wait()
        shakedown.wait_for_service_endpoint_removal('pyfw')

    with shakedown.marathon_on_marathon():
        delete_all_apps_wait()
        client = marathon.create_client()
        client.add_app(common.fake_framework_app())
        shakedown.deployment_wait()

    try:
        shakedown.wait_for_service_endpoint('pyfw', 15)
        assert False, 'MoM shoud NOT create a service endpoint'
    except:
        assert True
        pass


def partition_agent(hostname):
    """Partition a node from all network traffic except for SSH and loopback"""

    shakedown.copy_file_to_agent(hostname, "{}/net-services-agent.sh".format(fixture_dir()))
    print("partitioning {}".format(hostname))
    shakedown.run_command_on_agent(hostname, 'sh net-services-agent.sh fail')


def reconnect_agent(hostname):
    """Reconnect a node to cluster"""

    shakedown.run_command_on_agent(hostname, 'sh net-services-agent.sh')
