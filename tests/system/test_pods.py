"""Marathon job acceptance tests for DC/OS."""

import pytest

from shakedown import *
from dcos import marathon, util
from utils import fixture_dir, get_resource, wait_for_deployment

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def _pods_json(file="simple-pods.json"):
    return get_resource("{}/{}".format(fixture_dir(),file))


def _clear_pods():
    client = marathon.create_client()
    pods = client.list_pod()
    for pod in pods:
        client.remove_pod(pod["id"], True)
    wait_for_deployment(client)


def _pods_url(path):
    return "v2/pods/" + path


def _pod_status_url(pod_id):
    path = pod_id + "/::status"
    return _pods_url(path)


def _pod_status(client, pod_id):
    url = _pod_status_url(pod_id)
    return client._parse_json(client._rpc.http_req(http.get, url))


@pytest.mark.sanity
def test_create_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    _clear_pods()
    client = marathon.create_client()
    pod_id = "/pod-create"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    wait_for_deployment(client)
    pod = client.show_pod(pod_id)
    assert pod is not None


@pytest.mark.sanity
def test_remove_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    _clear_pods()
    pod_id = "/pod-remove"
    client = marathon.create_client()

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    wait_for_deployment(client)

    client.remove_pod(pod_id)
    wait_for_deployment(client)
    try:
        pod = client.show_pod(pod_id)
        assert False, "We shouldn't be here"
    except Exception as e:
        pass


@pytest.mark.sanity
def test_multi_pods():
    """Launch multiple instances of a pod"""
    _clear_pods()
    client = marathon.create_client()
    pod_id = "/pod-multi"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    wait_for_deployment(client)

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10

@pytest.mark.sanity
def test_scaleup_pods():
    """Scaling up a pod from 1 to 10"""
    _clear_pods()
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    client.add_pod(pod_json)
    wait_for_deployment(client)

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1

    pod_json["scaling"]["instances"] = 10
    client.update_pod(pod_id, pod_json)
    wait_for_deployment(client)
    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10

@pytest.mark.sanity
def test_scaledown_pods():
    """Scaling down a pod from 10 to 1"""
    _clear_pods()
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    wait_for_deployment(client)

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10

    pod_json["scaling"]["instances"] = 1
    client.update_pod(pod_id, pod_json)
    wait_for_deployment(client)
    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1

def teardown_module(module):
    _clear_pods()
