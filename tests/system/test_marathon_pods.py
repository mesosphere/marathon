"""Marathon job acceptance tests for DC/OS."""

import pytest
import uuid

from urllib.parse import urljoin

from common import *
from dcos import marathon, util
from shakedown import *
from utils import *


PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME) + "/"
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
        deployment_wait()
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


def test_create_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    client = marathon.create_client()
    pod_id = "/pod-create"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    deployment_wait()
    pod = client.show_pod(pod_id)
    assert pod is not None


def test_remove_pod():
    """Launch simple pod in DC/OS root marathon.
    """
    pod_id = "/pod-remove"
    client = marathon.create_client()

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    deployment_wait()

    client.remove_pod(pod_id)
    deployment_wait()
    try:
        pod = client.show_pod(pod_id)
        assert False, "We shouldn't be here"
    except Exception as e:
        pass


def test_multi_pods():
    """Launch multiple instances of a pod"""
    client = marathon.create_client()
    pod_id = "/pod-multi"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10


def test_scaleup_pods():
    """Scaling up a pod from 1 to 10"""
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    client.add_pod(pod_json)
    deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1

    pod_json["scaling"]["instances"] = 10
    client.update_pod(pod_id, pod_json)
    deployment_wait()
    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10


def test_scaledown_pods():
    """Scaling down a pod from 10 to 1"""
    client = marathon.create_client()
    pod_id = "/pod-scaleup"

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 10
    client.add_pod(pod_json)
    deployment_wait()

    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 10

    pod_json["scaling"]["instances"] = 1
    client.update_pod(pod_id, pod_json)
    deployment_wait()
    # there seems to be a race condition where
    # this is sometimes true after deploy
    time.sleep(1)
    status = _pod_status(client, pod_id)
    assert len(status["instances"]) == 1


def test_head_of_pods():
    """Tests the availability of pods via the API"""
    client = marathon.create_client()
    url = urljoin(DCOS_SERVICE_URL, _pods_url())
    result = http.head(url)
    assert result.status_code == 200


def test_version_pods():
    """Versions and reverting with pods"""
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    client.add_pod(pod_json)
    deployment_wait()

    time.sleep(1)
    pod_json["scaling"]["instances"] = 10
    client.update_pod(pod_id, pod_json)
    deployment_wait()

    time.sleep(1)
    versions = _pod_versions(client, pod_id)

    assert len(versions) == 2

    pod_version1 = _pod_version(client, pod_id, versions[0])
    pod_version2 = _pod_version(client, pod_id, versions[1])
    assert pod_version1["scaling"]["instances"] != pod_version2["scaling"]["instances"]


def test_pod_comm_via_volume():
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    # pods setup to have c1 write, ct2 read after 2 sec
    # there are 2 tasks, unless the file doesnt' exist, then there is 1
    pod_json = _pods_json('vol-pods.json')
    pod_json["id"] = pod_id
    client.add_pod(pod_json)
    deployment_wait()
    tasks = get_pod_tasks(pod_id)
    assert len(tasks) == 2
    time.sleep(4)
    assert len(tasks) == 2


def test_pod_restarts_on_nonzero_exit():
    client = marathon.create_client()

    pod_id = "/pod-{}".format(uuid.uuid4().hex)

    pod_json = _pods_json()
    pod_json["id"] = pod_id
    pod_json["scaling"]["instances"] = 1
    pod_json['containers'][0]['exec']['command']['shell'] = 'sleep 5; echo -n leaving; exit 2'
    client.add_pod(pod_json)
    deployment_wait()
    #
    time.sleep(1)
    tasks = get_pod_tasks(pod_id)
    initial_id1 = tasks[0]['id']
    initial_id2 = tasks[1]['id']

    time.sleep(6)
    tasks = get_pod_tasks(pod_id)
    for task in tasks:
        assert task['id'] != initial_id1
        assert task['id'] != initial_id2


def setup_function(function):
    _clear_pods()


def teardown_module(module):
    _clear_pods()
