"""Marathon acceptance tests for DC/OS regarding network partitioning"""

import os
import retrying
import shakedown
import time

from common import (app, block_port, cluster_info, delete_all_apps_wait, ensure_mom,
                    ip_of_mom, ip_other_than_mom, pin_to_host, systemctl_master)
from dcos import marathon
from shakedown import dcos_1_8, dcos_version_less_than, private_agent_2, required_private_agents
from utils import fixture_dir, get_resource, marathon_on_marathon


PACKAGE_NAME = 'marathon'
PACKAGE_APP_ID = 'marathon-user'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_APP_ID)
TOKEN = shakedown.dcos_acs_token()


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    ensure_mom()
    shakedown.wait_for_service_endpoint(PACKAGE_APP_ID)
    cluster_info()


def setup_function(function):
    shakedown.wait_for_service_endpoint('marathon-user')
    with marathon_on_marathon():
        delete_all_apps_wait()


@private_agent_2
def test_mom_with_master_process_failure():
    """ Launches a MoM, launches an app from MoM and restarts the master.
        It is expected that the service endpoint will come back and that the
        task_id is the original task_id
    """
    app_def = app('master-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()
        tasks = client.get_tasks('/master-failure')
        original_task_id = tasks[0]['id']
        systemctl_master()
        shakedown.wait_for_service_endpoint('marathon-user')

        @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
        def check_task_recovery():
            tasks = client.get_tasks('/master-failure')
            tasks[0]['id'] == original_task_id


@private_agent_2
def test_mom_when_disconnected_from_zk():
    """ Launch an app from MoM.  Then knock out access to zk from the MoM.
        Verify the task is still good.
    """
    app_def = app('zk-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
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


@private_agent_2
def test_mom_when_task_agent_bounced():
    """ Launch an app from MoM and restart the node the task is on.
    """
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
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


@private_agent_2
def test_mom_when_mom_agent_bounced():
    """ Launch an app from MoM and restart the node MoM is on.
    """
    app_def = app('agent-failure')
    mom_ip = ip_of_mom()
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
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


@private_agent_2
def test_mom_when_mom_process_killed():
    """ Launched a task from MoM then killed MoM.
    """
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
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


@private_agent_2
def test_mom_with_network_failure():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures
    simulated by knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: {}".format(mom_ip))

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with marathon_on_marathon():
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
    service_delay()

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    shakedown.wait_for_service_endpoint(PACKAGE_APP_ID)
    shakedown.wait_for_task("marathon-user", "sleep")

    with marathon_on_marathon():
        client = marathon.create_client()
        shakedown.wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"


@private_agent_2
def test_mom_with_network_failure_bounce_master():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by
    knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: {}".format(mom_ip))

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with marathon_on_marathon():
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
    service_delay()

    # bounce master
    shakedown.run_command_on_master("sudo systemctl restart dcos-mesos-master")

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    shakedown.wait_for_service_endpoint(PACKAGE_APP_ID)
    shakedown.wait_for_task("marathon-user", "sleep")

    with marathon_on_marathon():
        client = marathon.create_client()
        shakedown.wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"


def teardown_module(module):
    with marathon_on_marathon():
        delete_all_apps_wait()


def service_delay(delay=120):
    time.sleep(delay)


def partition_agent(hostname):
    """Partition a node from all network traffic except for SSH and loopback"""

    shakedown.copy_file_to_agent(hostname, "{}/net-services-agent.sh".format(fixture_dir()))
    print("partitioning {}".format(hostname))
    shakedown.run_command_on_agent(hostname, 'sh net-services-agent.sh fail')


def reconnect_agent(hostname):
    """Reconnect a node to cluster"""

    shakedown.run_command_on_agent(hostname, 'sh net-services-agent.sh')
