"""Marathon pod acceptance tests for DC/OS."""

import os
import pytest
import uuid
import retrying
import shakedown
import time

from distutils.version import LooseVersion
from urllib.parse import urljoin

from common import (block_port, cluster_info, event_fixture, get_pod_tasks, ip_other_than_mom,
                    pin_pod_to_host, restore_iptables, save_iptables)
from dcos import marathon, util, http
from shakedown import dcos_1_9, dcos_version_less_than, private_agent_2, required_private_agents
from utils import fixture_dir, get_resource, parse_json


PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME) + "/"
WAIT_TIME_IN_SECS = 300


def _pods_json(file="simple-pods.json"):
    return get_resource(os.path.join(fixture_dir(), file))


def _clear_pods():
    # clearing doesn't cause
    try:
        client = marathon.create_client()
        pods = client.list_pod()
        for pod in pods:
            client.remove_pod(pod["id"], True)
        shakedown.deployment_wait()
    except:
        pass


def _pods_url(path=""):
    return "v2/pods/" + path


def _pod_status_url(pod_id):
    path = pod_id + "/::status"
    return _pods_url(path)


def _pod_status(client, pod_id):
    url = urljoin(DCOS_SERVICE_URL, _pod_status_url(pod_id))
    return parse_json(http.get(url))


def _pod_instances_url(pod_id, instance_id):
    # '/{id}::instances/{instance}':
    path = pod_id + "/::instances/" + instance_id
    return _pods_url(path)


def _pod_versions_url(pod_id, version_id=""):
    # '/{id}::versions/{version_id}':
    path = pod_id + "/::versions/" + version_id
    return _pods_url(path)


def _pod_versions(client, pod_id):
    url = urljoin(DCOS_SERVICE_URL, _pod_versions_url(pod_id))
    return parse_json(http.get(url))


def _pod_version(client, pod_id, version_id):
    url = urljoin(DCOS_SERVICE_URL, _pod_versions_url(pod_id, version_id))
    return parse_json(http.get(url))


@dcos_1_9
def test_create_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    print("test")
    client = marathon.create_client()
    pod_id = "/pod-create"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    shakedown.deployment_wait()
    pod = client.show_pod(pod_id)
    assert pod is not None


@dcos_1_9
@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    """ Tests the Marathon event channnel specific to pod events.
    """
    client = marathon.create_client()
    pod_id = "/pod-create"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    # look for created
    @retrying.retry(stop_max_delay=10000)
    def check_deployment_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'event_stream_attached' in stdout
        assert 'pod_created_event' in stdout
        assert 'deployment_step_success' in stdout

    pod_json["scaling"]["instances"] = 3
    client.update_pod(pod_id, pod_json)
    shakedown.deployment_wait()

    # look for updated
    @retrying.retry(stop_max_delay=10000)
    def check_update_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'pod_updated_event' in stdout


@dcos_1_9
def test_remove_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    pod_id = "/pod-remove"
    client = marathon.create_client()

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    client.remove_pod(pod_id)
    shakedown.deployment_wait()
    try:
        pod = client.show_pod(pod_id)
        assert False, "We shouldn't be here"
    except Exception as e:
        pass


@dcos_1_9
def test_multi_pods():
    """Launch multiple instances of a pod"""
    client = marathon.create_client()
    pod_id = "/pod-multi"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10


@dcos_1_9
def test_scaleup_pods():
    """Scaling up a pod from 1 to 10"""
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1

    pod_json["scaling"]["instances"] = 10
    client.update_pod(pod_id, pod_json)
    shakedown.deployment_wait()
    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10


@dcos_1_9
def test_scaledown_pods():
    """Scaling down a pod from 10 to 1"""
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10

    pod_json["scaling"]["instances"] = 1
    client.update_pod(pod_id, pod_json)
    shakedown.deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1


@dcos_1_9
def test_head_of_pods():
    """Tests the availability of pods via the API"""
    client = marathon.create_client()
    url = urljoin(DCOS_SERVICE_URL, _pods_url())
    result = http.head(url)
    assert result.status_code == 200


@dcos_1_9
def test_version_pods():
    """Versions and reverting with pods"""
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    pod_json["scaling"]["instances"] = 10
    client.update_pod(pod_id, pod_json)
    shakedown.deployment_wait()

    versions = _pod_versions(client, pod_id)

    assert len(versions) == 2

    pod_version1 = _pod_version(client, pod_id, versions[0])
    pod_version2 = _pod_version(client, pod_id, versions[1])
    assert pod_version1["scaling"]["instances"] != pod_version2["scaling"]["instances"]


@dcos_1_9
def test_pod_comm_via_volume():
    """ Confirms that 1 container can read data from a volume that was written
        from the other container.  Most of the test is in the `vol-pods.json`.
        The reading container will die if it can't read the file. So if there are 2 tasks after
        4 secs were are good.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    # pods setup to have c1 write, ct2 read after 2 sec
    # there are 2 tasks, unless the file doesnt' exist, then there is 1
    pod_json = _pods_json('vol-pods.json')
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    shakedown.deployment_wait()
    tasks = get_pod_tasks(pod_id)
    assert len(tasks) == 2
    time.sleep(4)
    assert len(tasks) == 2


@dcos_1_9
def test_pod_restarts_on_nonzero_exit():
    """ Confirm that pods will relaunch if 1 of the containers exits non-zero.
        2 new tasks with new task_ids will result.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    pod_json['containers'][0]['exec']['command']['shell'] = 'sleep 5; echo -n leaving; exit 2'
    client.add_pod(pod_json)
    shakedown.deployment_wait()
    #
    tasks = get_pod_tasks(pod_id)
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    time.sleep(6)  # 1 sec past the 5 sec sleep in test containers command
    tasks = get_pod_tasks(pod_id)
    for task in tasks:
        assert task['id'] != initial_id1
        assert task['id'] != initial_id2


@dcos_1_9
def test_pod_multi_port():
    """ Tests that 2 containers with a port each will properly provision with their unique port assignment.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json('pod-ports.json')
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    shakedown.deployment_wait()
    #
    time.sleep(1)
    pod = client.list_pod()[0]

    container1 = pod['instances'][0]['containers'][0]
    port1 = container1['endpoints'][0]['allocatedHostPort']
    container2 = pod['instances'][0]['containers'][1]
    port2 = container2['endpoints'][0]['allocatedHostPort']

    assert port1 != port2


@dcos_1_9
def test_pod_port_communication():
    """ Test that 1 container can establish a socket connection to the other container in the same pod.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json('pod-ports.json')
    pod_json["id"] = pod_id

    # sleeps 2, then container 2 checks communication with container 1.
    # if that timesout, the task completes resulting in 1 container running
    # otherwise it is expected that 2 containers are running.
    pod_json['containers'][1]['exec']['command']['shell'] = 'sleep 2; curl -m 2 localhost:$ENDPOINT_HTTPENDPOINT; if [ $? -eq 7 ]; then exit; fi; /opt/mesosphere/bin/python -m http.server $ENDPOINT_HTTPENDPOINT2'  # NOQA
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    tasks = get_pod_tasks(pod_id)
    assert len(tasks) == 2


@dcos_1_9
@private_agent_2
def test_pin_pod():
    """ Tests that we can pin a pod to a host.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json('pod-ports.json')
    pod_json["id"] = pod_id

    host = ip_other_than_mom()
    pin_pod_to_host(pod_json, host)
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    tasks = get_pod_tasks(pod_id)
    assert len(tasks) == 2

    pod = client.list_pod()[0]
    assert pod['instances'][0]['agentHostname'] == host


@dcos_1_9
def test_health_check():
    """ Tests that health checks work in pods.
    """
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json('pod-ports.json')
    pod_json["id"] = pod_id

    client.add_pod(pod_json)
    shakedown.deployment_wait()

    tasks = get_pod_tasks(pod_id)
    c1_health = tasks[0]['statuses'][0]['healthy']
    c2_health = tasks[1]['statuses'][0]['healthy']

    assert c1_health
    assert c2_health


@dcos_1_9
@private_agent_2
def test_health_failed_check():
    """ Deploys a pod with good health checks, then partitions the network and verifies
        the tasks return with new task ids.
    """
    client = marathon.create_client()

    pod_id = "/pod-ken".format(uuid.uuid4().hex)

    pod_json = _pods_json('pod-ports.json')
    pod_json["id"] = pod_id
    host = ip_other_than_mom()
    pin_pod_to_host(pod_json, host)
    client.add_pod(pod_json)
    shakedown.deployment_wait()

    tasks = get_pod_tasks(pod_id)
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    pod = client.list_pod()[0]
    container1 = pod['instances'][0]['containers'][0]
    port = container1['endpoints'][0]['allocatedHostPort']

    save_iptables(host)
    block_port(host, port)
    time.sleep(7)
    restore_iptables(host)
    shakedown.deployment_wait()

    tasks = get_pod_tasks(pod_id)
    for task in tasks:
        assert task['id'] != initial_id1
        assert task['id'] != initial_id2


def setup_function(function):
    _clear_pods()


def setup_module(module):
    cluster_info()


def teardown_module(module):
    _clear_pods()
