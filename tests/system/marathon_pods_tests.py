"""Marathon pod acceptance tests for DC/OS."""

import common
import json
import os
import pods
import pytest
import retrying
import shakedown
import time

from datetime import timedelta
from dcos import marathon, http
from shakedown import dcos_version_less_than, marthon_version_less_than, required_private_agents
from urllib.parse import urljoin

from fixtures import wait_for_marathon_and_cleanup
from utils import parse_json


PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME) + "/"


def get_pods_url(path=""):
    return "v2/pods/" + path


def get_pod_status_url(pod_id):
    path = pod_id + "/::status"
    return get_pods_url(path)


def get_pod_status(pod_id):
    url = urljoin(DCOS_SERVICE_URL, get_pod_status_url(pod_id))
    return parse_json(http.get(url))


def get_pod_instances_url(pod_id, instance_id):
    # '/{id}::instances/{instance}':
    path = pod_id + "/::instances/" + instance_id
    return get_pods_url(path)


def get_pod_versions_url(pod_id, version_id=""):
    # '/{id}::versions/{version_id}':
    path = pod_id + "/::versions/" + version_id
    return get_pods_url(path)


def get_pod_versions(pod_id):
    url = urljoin(DCOS_SERVICE_URL, get_pod_versions_url(pod_id))
    return parse_json(http.get(url))


def get_pod_version(pod_id, version_id):
    url = urljoin(DCOS_SERVICE_URL, get_pod_versions_url(pod_id, version_id))
    return parse_json(http.get(url))


@shakedown.dcos_1_9
def test_create_pod():
    """Launch simple pod in DC/OS root marathon."""

    pod_def = pods.simple_pod()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    pod = client.show_pod(pod_def["id"])
    assert pod is not None, "The pod has not been created"


@common.marathon_1_5
@pytest.mark.skipif("shakedown.ee_version() is None")
@pytest.mark.skipif("common.docker_env_not_set()")
def test_create_pod_with_private_image():
    """Deploys a pod with a private Docker image, using Mesos containerizer."""

    if not common.is_enterprise_cli_package_installed():
        common.install_enterprise_cli_package()

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']

    secret_name = "pullConfig"
    secret_value_json = common.create_docker_pull_config_json(username, password)
    secret_value = json.dumps(secret_value_json)

    pod_def = pods.private_docker_pod()
    common.create_secret(secret_name, secret_value)
    client = marathon.create_client()

    try:
        client.add_pod(pod_def)
        shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())
        pod = client.show_pod(pod_def["id"])
        assert pod is not None, "The pod has not been created"
    finally:
        common.delete_secret(secret_name)


@shakedown.dcos_1_9
@pytest.mark.usefixtures("wait_for_marathon_and_cleanup", "events_to_file")
def test_event_channel_for_pods():
    """Tests the Marathon event channel specific to pod events."""

    pod_def = pods.simple_pod()

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write files.
    if shakedown.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_root_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    # look for created
    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def check_deployment_message():
        status, stdout = shakedown.run_command_on_master('cat events.txt')
        assert 'event_stream_attached' in stdout, "event_stream_attached event has not been produced"
        assert 'pod_created_event' in stdout, "pod_created_event event has not been produced"
        assert 'deployment_step_success' in stdout, "deployment_step_success event has not beed produced"

    check_deployment_message()

    pod_def["scaling"]["instances"] = 3
    client.update_pod(pod_def["id"], pod_def)
    shakedown.deployment_wait()

    # look for updated
    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def check_update_message():
        status, stdout = shakedown.run_command_on_master('cat events.txt')
        assert 'pod_updated_event' in stdout, 'pod_update_event event has not been produced'

    check_update_message()


@shakedown.dcos_1_9
def test_remove_pod():
    """Launches a pod and then removes it."""

    pod_def = pods.simple_pod()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    client.remove_pod(pod_def["id"])
    shakedown.deployment_wait()

    try:
        _ = client.show_pod(pod_def["id"])
    except:
        pass
    else:
        assert False, "The pod has not been removed"


@shakedown.dcos_1_9
def test_multi_instance_pod():
    """Launches a pod with multiple instances."""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 10

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    status = get_pod_status(pod_def["id"])
    assert len(status["instances"]) == 10, \
        "The number of instances is {}, but 10 was expected".format(len(status["instances"]))


@shakedown.dcos_1_9
def test_scale_up_pod():
    """Scales up a pod from 1 to 10 instances."""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 1

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    status = get_pod_status(pod_def["id"])
    assert len(status["instances"]) == 1, \
        "The number of instances is {}, but 1 was expected".format(len(status["instances"]))

    pod_def["scaling"]["instances"] = 10
    client.update_pod(pod_def["id"], pod_def)
    shakedown.deployment_wait()

    status = get_pod_status(pod_def["id"])
    assert len(status["instances"]) == 10, \
        "The number of instances is {}, but 10 was expected".format(len(status["instances"]))


@shakedown.dcos_1_9
def test_scale_down_pod():
    """Scales down a pod from 10 to 1 instance."""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 10

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    status = get_pod_status(pod_def["id"])
    assert len(status["instances"]) == 10, \
        "The number of instances is {}, but 10 was expected".format(len(status["instances"]))

    pod_def["scaling"]["instances"] = 1
    client.update_pod(pod_def["id"], pod_def)
    shakedown.deployment_wait()

    status = get_pod_status(pod_def["id"])
    assert len(status["instances"]) == 1, \
        "The number of instances is {}, but 1 was expected".format(len(status["instances"]))


@shakedown.dcos_1_9
def test_head_request_to_pods_endpoint():
    """Tests the pods HTTP end-point by firing a HEAD request to it."""

    url = urljoin(DCOS_SERVICE_URL, get_pods_url())
    result = http.head(url)
    assert result.status_code == 200


@shakedown.dcos_1_9
def test_create_and_update_pod():
    """Versions and reverting with pods"""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 1

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    pod_def["scaling"]["instances"] = 10
    client.update_pod(pod_def["id"], pod_def)
    shakedown.deployment_wait()

    versions = get_pod_versions(pod_def["id"])
    assert len(versions) == 2, "The number of versions is {}, but 2 was expected".format(len(versions))

    version1 = get_pod_version(pod_def["id"], versions[0])
    version2 = get_pod_version(pod_def["id"], versions[1])
    assert version1["scaling"]["instances"] != version2["scaling"]["instances"], \
        "Two pod versions have the same number of instances: {}, but they should not".format(
            version1["scaling"]["instances"])


# known to fail in strict mode
@pytest.mark.skipif("shakedown.ee_version() == 'strict'")
@shakedown.dcos_1_9
def test_two_pods_with_shared_volume():
    """Confirms that 1 container can read data in a volume that was written from the other container.
       The reading container fails if it can't read the file. So if there are 2 tasks after
       4 seconds we are good.
    """

    pod_def = pods.ephemeral_volume_pod()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))

    time.sleep(4)

    tasks = common.get_pod_tasks(pod_def["id"])
    assert len(tasks) == 2, "The number of tasks is {} after sleeping, but 2 was expected".format(len(tasks))


@shakedown.dcos_1_9
def test_pod_restarts_on_nonzero_exit_code():
    """Verifies that a pod get restarted in case one of its containers exits with a non-zero code.
       As a result, after restart, there should be two new tasks for different IDs.
    """

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 1
    pod_def['containers'][0]['exec']['command']['shell'] = 'sleep 5; echo -n leaving; exit 2'

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    time.sleep(6)  # 1 sec past the 5 sec sleep in one of the container's command
    tasks = common.get_pod_tasks(pod_def["id"])
    for task in tasks:
        assert task['id'] != initial_id1, "Got the same task ID"
        assert task['id'] != initial_id2, "Got the same task ID"


@shakedown.dcos_1_9
def test_pod_multi_port():
    """A pod with two containers is properly provisioned so that each container has a unique port."""

    pod_def = pods.ports_pod()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    pod = client.show_pod(pod_def["id"])

    container1 = pod['instances'][0]['containers'][0]
    port1 = container1['endpoints'][0]['allocatedHostPort']
    container2 = pod['instances'][0]['containers'][1]
    port2 = container2['endpoints'][0]['allocatedHostPort']

    assert port1 != port2, "Containers' ports are equal, but they should be different"


@shakedown.dcos_1_9
def test_pod_port_communication():
    """ Test that 1 container can establish a socket connection to the other container in the same pod.
    """

    pod_def = pods.ports_pod()

    cmd = 'sleep 2; ' \
          'curl -m 2 localhost:$ENDPOINT_HTTPENDPOINT; ' \
          'if [ $? -eq 7 ]; then exit; fi; ' \
          '/opt/mesosphere/bin/python -m http.server $ENDPOINT_HTTPENDPOINT2'
    pod_def['containers'][1]['exec']['command']['shell'] = cmd

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))


@shakedown.dcos_1_9
@shakedown.private_agents(2)
def test_pin_pod():
    """Tests that a pod can be pinned to a specific host."""

    pod_def = pods.ports_pod()

    host = common.ip_other_than_mom()
    common.pin_pod_to_host(pod_def, host)

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))

    pod = client.list_pod()[0]
    assert pod['instances'][0]['agentHostname'] == host, "The pod didn't get pinned to {}".format(host)


@shakedown.dcos_1_9
def test_pod_health_check():
    """Tests that health checks work for pods."""

    pod_def = pods.ports_pod()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    c1_health = tasks[0]['statuses'][0]['healthy']
    c2_health = tasks[1]['statuses'][0]['healthy']

    assert c1_health, "One of the pod's tasks is unhealthy"
    assert c2_health, "One of the pod's tasks is unhealthy"


@shakedown.dcos_1_9
def test_pod_with_container_network():
    """Tests creation of a pod with a "container" network, and its HTTP endpoint accessibility."""

    pod_def = pods.container_net_pod()

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write to /var/log within the container.
    if shakedown.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_root_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])

    network_info = tasks[0]['statuses'][0]['container_status']['network_infos'][0]
    assert network_info['name'] == "dcos", \
        "The network name is {}, but 'dcos' was expected".format(network_info['name'])

    container_ip = network_info['ip_addresses'][0]['ip_address']
    assert container_ip is not None, "No IP address has been assigned to the pod's container"

    url = "http://{}:80/".format(container_ip)
    common.assert_http_code(url)


@common.marathon_1_5
def test_pod_with_container_bridge_network():
    """Tests creation of a pod with a "container/bridge" network, and its HTTP endpoint accessibility."""

    pod_def = pods.container_bridge_pod()

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write to /var/log within the container.
    if shakedown.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_root_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    task = common.get_pod_tasks(pod_def["id"])[0]
    network_info = task['statuses'][0]['container_status']['network_infos'][0]
    assert network_info['name'] == "mesos-bridge", \
        "The network is {}, but mesos-bridge was expected".format(network_info['name'])

    # get the port on the host
    port = task['discovery']['ports']['ports'][0]['number']

    # the agent IP:port will be routed to the bridge IP:port
    # test against the agent_ip, however it is hard to get.. translating from
    # slave_id
    agent_ip = common.agent_hostname_by_id(task['slave_id'])
    assert agent_ip is not None, "Failed to get the agent IP address"
    container_ip = network_info['ip_addresses'][0]['ip_address']
    assert agent_ip != container_ip, "The container IP address is the same as the agent one"

    url = "http://{}:{}/".format(agent_ip, port)
    common.assert_http_code(url)


@shakedown.dcos_1_9
@shakedown.private_agents(2)
def test_pod_health_failed_check():
    """Deploys a pod with correct health checks, then partitions the network and verifies that
       the tasks get restarted with new task IDs.
    """

    pod_def = pods.ports_pod()

    host = common.ip_other_than_mom()
    common.pin_pod_to_host(pod_def, host)

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    pod = client.list_pod()[0]
    container1 = pod['instances'][0]['containers'][0]
    port = container1['endpoints'][0]['allocatedHostPort']

    common.save_iptables(host)
    common.block_port(host, port)
    time.sleep(7)
    common.restore_iptables(host)
    shakedown.deployment_wait()

    tasks = common.get_pod_tasks(pod_def["id"])
    for task in tasks:
        assert task['id'] != initial_id1, "One of the tasks has not been restarted"
        assert task['id'] != initial_id2, "One of the tasks has not been restarted"
