"""Marathon acceptance tests for DC/OS regarding network partitioning"""

import os

import pytest
import requests

from common import *
from shakedown import *
from utils import *


PACKAGE_NAME = 'marathon'
PACKAGE_APP_ID = 'marathon-user'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_APP_ID)
TOKEN = dcos_acs_token()


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print(agents)
    if len(agents) < 2:
        assert False, "Network tests require at least 2 private agents"

    ensure_mom()
    wait_for_service_url(PACKAGE_APP_ID)
    cluster_info()


def setup_function(function):
    with marathon_on_marathon():
        delete_all_apps_wait()


def test_mom_with_master_process_failure():
    app_def = app('master-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/master-failure')
        original_task_id = tasks[0]['id']
        systemctl_master()
        time.sleep(5)
        tasks = client.get_tasks('/master-failure')
        tasks[0]['id'] == original_task_id


def test_mom_with_master_node_failure():
    app_def = app('master-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/master-failure')
        original_task_id = tasks[0]['id']
        restart_master_node()
        wait_for_task('marathon', 'marathon-user', 300)
        tasks = client.get_tasks('/master-failure')
        tasks[0]['id'] == original_task_id


def test_mom_when_disconnected_from_zk():
    app_def = app('zk-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/zk-failure')
        original_task_id = tasks[0]['id']

        with iptable_rules(host):
            block_port(host, 2181)
            time.sleep(10)
        time.sleep(5)
        tasks = client.get_tasks('/zk-failure')
        tasks[0]['id'] == original_task_id


def test_mom_when_task_agent_bounced():
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/agent-failure')
        original_task_id = tasks[0]['id']

        restart_agent(host)
        time.sleep(5)
        tasks = client.get_tasks('/agent-failure')
        tasks[0]['id'] == original_task_id


def test_mom_when_mom_agent_bounced():
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/agent-failure')
        original_task_id = tasks[0]['id']

        restart_agent(ip_of_mom())
        time.sleep(5)
        tasks = client.get_tasks('/agent-failure')
        tasks[0]['id'] == original_task_id


def test_mom_when_mom_process_killed():
    app_def = app('agent-failure')
    host = ip_other_than_mom()
    pin_to_host(app_def, host)
    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        deployment_wait()
        tasks = client.get_tasks('/agent-failure')
        original_task_id = tasks[0]['id']

        kill_process_on_host(ip_of_mom(), 'marathon-assembly')
        time.sleep(5)
        wait_for_task('marathon', 'marathon-user', 300)
    # print(wait_for_task('marathon', 'marathon-user')['state'] == 'TASK_RUNNING')
        wait_for_service_url('marathon-user')
        tasks = client.get_tasks('/agent-failure')
        tasks[0]['id'] == original_task_id


@pytest.mark.sanity
def test_mom_with_network_failure():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures
    simulated by knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: " + mom_ip)

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        wait_for_task("marathon-user", "sleep")
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

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    wait_for_service_url(PACKAGE_APP_ID)
    wait_for_task("marathon-user", "sleep")

    with marathon_on_marathon():
        client = marathon.create_client()
        wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"



@pytest.mark.sanity
def test_mom_with_network_failure_bounce_master():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by
    knocking out ports
    """

    # get MoM ip
    mom_ip = ip_of_mom()
    print("MoM IP: " + mom_ip)

    app_def = get_resource("{}/large-sleep.json".format(fixture_dir()))

    with marathon_on_marathon():
        client = marathon.create_client()
        client.add_app(app_def)
        wait_for_task("marathon-user", "sleep")
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
    run_command_on_master("sudo systemctl restart dcos-mesos-master")

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    wait_for_service_url(PACKAGE_APP_ID)
    wait_for_task("marathon-user", "sleep")

    with marathon_on_marathon():
        client = marathon.create_client()
        wait_for_task("marathon-user", "sleep")
        tasks = client.get_tasks('sleep')
        current_sleep_task_id = tasks[0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"


def teardown_module(module):
    with marathon_on_marathon():
        delete_all_apps_wait()


def service_delay(delay=120):
    time.sleep(delay)


def print_json(response, print_response=True):
    response_json = response.json()
    if print_response:
        print("Got response for %s %s:\n%s" % (
            response.request.method, response.request.url, response_json))
    return response_json


def partition_agent(hostname):
    """Partition a node from all network traffic except for SSH and loopback"""

    copy_file_to_agent(hostname, "{}/net-services-agent.sh".format(fixture_dir()))
    print("partitioning {}".format(hostname))
    run_command_on_agent(hostname, 'sh net-services-agent.sh fail')


def reconnect_agent(hostname):
    """Reconnect a node to cluster"""

    run_command_on_agent(hostname, 'sh net-services-agent.sh')
