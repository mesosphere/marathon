"""Marathon pod acceptance tests for DC/OS."""

import common
import json
import os
import pods
import pytest
import retrying
import requests

import shakedown
import time
import logging

from shakedown.clients import marathon, dcos_service_url
from shakedown.clients.authentication import dcos_acs_token, DCOSAcsAuth
from shakedown.clients.rpcclient import verify_ssl
from shakedown.dcos.agent import required_private_agents # NOQA F401
from shakedown.dcos.cluster import dcos_version_less_than # NOQA F401
from shakedown.dcos.command import run_command_on_master
from shakedown.dcos.marathon import deployment_wait, marathon_version_less_than # NOQA F401
from urllib.parse import urljoin

from fixtures import sse_events, wait_for_marathon_and_cleanup # NOQA

logger = logging.getLogger(__name__)

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME) + "/"


def get_pods_url(path=""):
    return "v2/pods/" + path


def get_pod_status_url(pod_id):
    path = pod_id + "/::status"
    return get_pods_url(path)


def get_pod_status(pod_id):
    url = urljoin(DCOS_SERVICE_URL, get_pod_status_url(pod_id))
    auth = DCOSAcsAuth(dcos_acs_token())
    return requests.get(url, auth=auth, verify=verify_ssl()).json()


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
    auth = DCOSAcsAuth(dcos_acs_token())
    return requests.get(url, auth=auth, verify=verify_ssl()).json()


def get_pod_version(pod_id, version_id):
    url = urljoin(DCOS_SERVICE_URL, get_pod_versions_url(pod_id, version_id))
    auth = DCOSAcsAuth(dcos_acs_token())
    return requests.get(url, auth=auth, verify=verify_ssl()).json()


@shakedown.dcos.cluster.dcos_1_9
def test_create_pod():
    """Launch simple pod in DC/OS root marathon."""

    pod_def = pods.simple_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    pod = client.show_pod(pod_id)
    assert pod is not None, "The pod has not been created"


@common.marathon_1_5
@pytest.mark.skipif("shakedown.dcos.cluster.ee_version() is None")
@pytest.mark.skipif("common.docker_env_not_set()")
def test_create_pod_with_private_image():
    """Deploys a pod with a private Docker image, using Mesos containerizer.
        This method relies on the global `install_enterprise_cli` fixture to install the
        enterprise-cli-package.
    """

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']

    secret_name = "pullconfig"
    secret_value_json = common.create_docker_pull_config_json(username, password)
    secret_value = json.dumps(secret_value_json)

    pod_def = pods.private_docker_pod()
    pod_id = pod_def['id']
    common.create_secret(secret_name, secret_value)
    client = marathon.create_client()

    try:
        client.add_pod(pod_def)
        deployment_wait(service_id=pod_id, max_attempts=300)
        pod = client.show_pod(pod_id)
        assert pod is not None, "The pod has not been created"
    finally:
        common.delete_secret(secret_name)


@shakedown.dcos.cluster.dcos_1_9 # NOQA F811
@pytest.mark.usefixtures("wait_for_marathon_and_cleanup")
@pytest.mark.asyncio
async def test_event_channel_for_pods(sse_events):
    """Tests the Marathon event channel specific to pod events."""

    await common.assert_event('event_stream_attached', sse_events)

    pod_def = pods.simple_pod()
    pod_id = pod_def['id']

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write files.
    if shakedown.dcos.cluster.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    await common.assert_event('pod_created_event', sse_events)
    await common.assert_event('deployment_step_success', sse_events)

    pod_def["scaling"]["instances"] = 3
    client.update_pod(pod_id, pod_def)
    deployment_wait(service_id=pod_id)

    await common.assert_event('pod_updated_event', sse_events)


@shakedown.dcos.cluster.dcos_1_9
def test_remove_pod():
    """Launches a pod and then removes it."""

    pod_def = pods.simple_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    client.remove_pod(pod_id)
    deployment_wait(service_id=pod_id)

    try:
        client.show_pod(pod_id)
    except requests.HTTPError as e:
        assert e.response.status_code == 404
    else:
        assert False, "The pod has not been removed"


@shakedown.dcos.cluster.dcos_1_9
def test_multi_instance_pod():
    """Launches a pod with multiple instances."""

    pod_def = pods.simple_pod()
    pod_id = pod_def['id']
    pod_def["scaling"]["instances"] = 3

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    status = get_pod_status(pod_id)
    assert len(status["instances"]) == 3, \
        "The number of instances is {}, but 3 was expected".format(len(status["instances"]))


@shakedown.dcos.cluster.dcos_1_9
def test_scale_up_pod():
    """Scales up a pod from 1 to 3 instances."""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 1
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    status = get_pod_status(pod_id)
    assert len(status["instances"]) == 1, \
        "The number of instances is {}, but 1 was expected".format(len(status["instances"]))

    pod_def["scaling"]["instances"] = 3
    client.update_pod(pod_id, pod_def)
    deployment_wait(service_id=pod_id)

    status = get_pod_status(pod_id)
    assert len(status["instances"]) == 3, \
        "The number of instances is {}, but 3 was expected".format(len(status["instances"]))


@shakedown.dcos.cluster.dcos_1_9
def test_scale_down_pod():
    """Scales down a pod from 3 to 1 instance."""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 3
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    status = get_pod_status(pod_id)
    assert len(status["instances"]) == 3, \
        "The number of instances is {}, but 3 was expected".format(len(status["instances"]))

    pod_def["scaling"]["instances"] = 1
    client.update_pod(pod_id, pod_def)
    deployment_wait(service_id=pod_id)

    status = get_pod_status(pod_id)
    assert len(status["instances"]) == 1, \
        "The number of instances is {}, but 1 was expected".format(len(status["instances"]))


@shakedown.dcos.cluster.dcos_1_9
def test_head_request_to_pods_endpoint():
    """Tests the pods HTTP end-point by firing a HEAD request to it."""

    url = urljoin(DCOS_SERVICE_URL, get_pods_url())
    auth = DCOSAcsAuth(dcos_acs_token())
    result = requests.head(url, auth=auth, verify=verify_ssl())
    assert result.status_code == 200


@shakedown.dcos.cluster.dcos_1_9
def test_create_and_update_pod():
    """Versions and reverting with pods"""

    pod_def = pods.simple_pod()
    pod_def["scaling"]["instances"] = 1
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    pod_def["scaling"]["instances"] = 3
    client.update_pod(pod_id, pod_def)
    deployment_wait(service_id=pod_id)

    versions = get_pod_versions(pod_id)
    assert len(versions) == 2, "The number of versions is {}, but 2 was expected".format(len(versions))

    version1 = get_pod_version(pod_id, versions[0])
    version2 = get_pod_version(pod_id, versions[1])
    assert version1["scaling"]["instances"] != version2["scaling"]["instances"], \
        "Two pod versions have the same number of instances: {}, but they should not".format(
            version1["scaling"]["instances"])


# known to fail in strict mode
@pytest.mark.skipif("shakedown.dcos.cluster.ee_version() == 'strict'")
@shakedown.dcos.cluster.dcos_1_9
def test_two_pods_with_shared_volume():
    """Confirms that 1 container can read data in a volume that was written from the other container.
       The reading container fails if it can't read the file. So if there are 2 tasks after
       4 seconds we are good.
    """

    pod_def = pods.ephemeral_volume_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))

    time.sleep(4)

    tasks = common.get_pod_tasks(pod_id)
    assert len(tasks) == 2, "The number of tasks is {} after sleeping, but 2 was expected".format(len(tasks))


@shakedown.dcos.cluster.dcos_1_9
def test_pod_restarts_on_nonzero_exit_code():
    """Verifies that a pod get restarted in case one of its containers exits with a non-zero code.
       As a result, after restart, there should be two new tasks for different IDs.
    """

    pod_def = pods.simple_pod()
    pod_id = pod_def['id']
    pod_def["scaling"]["instances"] = 1
    pod_def['containers'][0]['exec']['command']['shell'] = 'sleep 5; echo -n leaving; exit 2'

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    time.sleep(6)  # 1 sec past the 5 sec sleep in one of the container's command
    tasks = common.get_pod_tasks(pod_id)
    for task in tasks:
        assert task['id'] != initial_id1, "Got the same task ID"
        assert task['id'] != initial_id2, "Got the same task ID"


@shakedown.dcos.cluster.dcos_1_9
def test_pod_multi_port():
    """A pod with two containers is properly provisioned so that each container has a unique port."""

    pod_def = pods.ports_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    pod = client.show_pod(pod_id)

    container1 = pod['instances'][0]['containers'][0]
    port1 = container1['endpoints'][0]['allocatedHostPort']
    container2 = pod['instances'][0]['containers'][1]
    port2 = container2['endpoints'][0]['allocatedHostPort']

    assert port1 != port2, "Containers' ports are equal, but they should be different"


@shakedown.dcos.cluster.dcos_1_9
def test_pod_port_communication():
    """ Test that 1 container can establish a socket connection to the other container in the same pod.
    """

    pod_def = pods.ports_pod()
    pod_id = pod_def['id']

    cmd = 'sleep 2; ' \
          'curl -m 2 localhost:$ENDPOINT_HTTPENDPOINT; ' \
          'if [ $? -eq 7 ]; then exit; fi; ' \
          '/opt/mesosphere/bin/python -m http.server $ENDPOINT_HTTPENDPOINT2'
    pod_def['containers'][1]['exec']['command']['shell'] = cmd

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))


@shakedown.dcos.cluster.dcos_1_9
@shakedown.dcos.agent.private_agents(2)
def test_pin_pod():
    """Tests that a pod can be pinned to a specific host."""

    pod_def = pods.ports_pod()
    pod_id = pod_def['id']

    host = common.ip_other_than_mom()
    common.pin_pod_to_host(pod_def, host)

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    assert len(tasks) == 2, "The number of tasks is {} after deployment, but 2 was expected".format(len(tasks))

    pod = client.list_pod()[0]
    assert pod['instances'][0]['agentHostname'] == host, "The pod didn't get pinned to {}".format(host)


@shakedown.dcos.cluster.dcos_1_9
def test_pod_health_check():
    """Tests that health checks work for pods."""

    pod_def = pods.ports_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    @retrying.retry(wait_fixed=1000, wait_exponential_max=30000, retry_on_exception=common.ignore_exception)
    def assert_all_pods_healthy(pod_id):
        tasks = common.get_pod_tasks(pod_id)
        for task in tasks:
            health = common.running_task_status(task['statuses'])['healthy']
            assert health, "One of the pod's tasks (%s) is unhealthy" % (task['name'])

    assert_all_pods_healthy(pod_id)


@shakedown.dcos.cluster.dcos_1_9
def test_pod_with_container_network():
    """Tests creation of a pod with a "container" network, and its HTTP endpoint accessibility."""

    pod_def = pods.container_net_pod()
    pod_id = pod_def['id']

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write to /var/log within the container.
    if shakedown.dcos.cluster.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    task = common.task_by_name(common.get_pod_tasks(pod_id), "nginx")

    network_info = common.running_status_network_info(task['statuses'])
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
    pod_id = pod_def['id']

    # In strict mode all tasks are started as user `nobody` by default and `nobody`
    # doesn't have permissions to write to /var/log within the container.
    if shakedown.dcos.cluster.ee_version() == 'strict':
        pod_def['user'] = 'root'
        common.add_dcos_marathon_user_acls()

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    task = common.task_by_name(common.get_pod_tasks(pod_id), "nginx")
    network_info = common.running_status_network_info(task['statuses'])
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


@shakedown.dcos.cluster.dcos_1_9
@shakedown.dcos.agent.private_agents(2)
def test_pod_health_failed_check():
    """Deploys a pod with correct health checks, then partitions the network and verifies that
       the tasks get restarted with new task IDs.
    """

    pod_def = pods.ports_pod()
    pod_id = pod_def['id']

    host = common.ip_other_than_mom()
    common.pin_pod_to_host(pod_def, host)

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    pod = client.list_pod()[0]
    container1 = pod['instances'][0]['containers'][0]
    port = container1['endpoints'][0]['allocatedHostPort']

    common.block_iptable_rules_for_seconds(host, port, 7, block_input=True, block_output=False)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    for new_task in tasks:
        new_task_id = new_task['id']
        assert new_task_id != initial_id1, f"Task {new_task_id} has not been restarted" # NOQA E999
        assert new_task_id != initial_id2, f"Task {new_task_id} has not been restarted"


@common.marathon_1_6
def test_pod_with_persistent_volume():
    pod_def = pods.persistent_volume_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)

    host = common.running_status_network_info(tasks[0]['statuses'])['ip_addresses'][0]['ip_address']
    port1 = tasks[0]['discovery']['ports']['ports'][0]["number"]
    port2 = tasks[1]['discovery']['ports']['ports'][0]["number"]
    path1 = tasks[0]['container']['volumes'][0]['container_path']
    path2 = tasks[1]['container']['volumes'][0]['container_path']
    logger.info('Deployd two containers on {}:{}/{} and {}:{}/{}'.format(host, port1, path1, host, port2, path2))

    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=60, retry_on_exception=common.ignore_exception)
    def check_http_endpoint(port, path):
        cmd = "curl {}:{}/{}/foo".format(host, port, path)
        run, data = run_command_on_master(cmd)
        assert run, "{} did not succeed".format(cmd)
        assert data == 'hello\n', "'{}' was not equal to hello\\n".format(data)

    check_http_endpoint(port1, path1)
    check_http_endpoint(port2, path2)


@common.marathon_1_6
def test_pod_with_persistent_volume_recovers():
    pod_def = pods.persistent_volume_pod()
    pod_id = pod_def['id']

    client = marathon.create_client()
    client.add_pod(pod_def)
    deployment_wait(service_id=pod_id)

    tasks = common.get_pod_tasks(pod_id)
    assert len(tasks) == 2, "The number of pod tasks is {}, but is expected to be 2".format(len(tasks))

    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def wait_for_status_network_info():
        tasks = common.get_pod_tasks(pod_id)
        # the following command throws exceptions if there are no tasks in TASK_RUNNING state
        common.running_status_network_info(tasks[0]['statuses'])

    wait_for_status_network_info()
    host = common.running_status_network_info(tasks[0]['statuses'])['ip_addresses'][0]['ip_address']

    task_id1 = tasks[0]['id']
    task_id2 = tasks[1]['id']

    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def kill_task(host, pattern):
        pids = common.kill_process_on_host(host, pattern)
        assert len(pids) != 0, "no task got killed on {} for pattern {}".format(host, pattern)

    kill_task(host, '[h]ttp\\.server')

    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def wait_for_pod_recovery():
        tasks = common.get_pod_tasks(pod_id)
        assert len(tasks) == 2, "The number of tasks is {} after recovery, but 2 was expected".format(len(tasks))

        old_task_ids = [task_id1, task_id2]
        new_task_id1 = tasks[0]['id']
        new_task_id2 = tasks[1]['id']

        assert new_task_id1 not in old_task_ids, \
            "The task ID has not changed, and is still {}".format(new_task_id1)
        assert new_task_id2 not in old_task_ids, \
            "The task ID has not changed, and is still {}".format(new_task_id2)

    wait_for_pod_recovery()
    wait_for_status_network_info()

    tasks = common.get_pod_tasks(pod_id)
    assert host == common.running_status_network_info(tasks[0]['statuses'])['ip_addresses'][0]['ip_address'], \
        "the pod has been restarted on another host"

    port1 = tasks[0]['discovery']['ports']['ports'][0]["number"]
    port2 = tasks[1]['discovery']['ports']['ports'][0]["number"]
    path1 = tasks[0]['container']['volumes'][0]['container_path']
    path2 = tasks[1]['container']['volumes'][0]['container_path']
    logger.info('Deployd two containers on {}:{}/{} and {}:{}/{}'.format(host, port1, path1, host, port2, path2))

    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def check_data(port, path):
        cmd = "curl {}:{}/{}/foo".format(host, port, path)
        run, data = run_command_on_master(cmd)
        assert run, "{} did not succeed".format(cmd)
        assert 'hello\nhello\n' in data, "'hello\nhello\n' not found in '{}'n".format(data)

    check_data(port1, path1)
    check_data(port2, path2)
