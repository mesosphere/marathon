import os.path
import uuid

from utils import make_id, get_resource


def pods_dir():
    return os.path.dirname(os.path.abspath(__file__))


def load_pod(pod_name):
    pod_path = os.path.join(pods_dir(), "{}.json".format(pod_name))
    pod = get_resource(pod_path)
    pod['id'] = make_id(pod_name)
    return pod


def simple_pod(pod_id=None):
    if pod_id is None:
        pod_id = '/simple-pod-{}'.format(uuid.uuid4().hex)
    pod = load_pod('simple-pod')
    pod['id'] = pod_id
    return pod


def private_docker_pod():
    return load_pod('private-docker-pod')


def ephemeral_volume_pod():
    return load_pod('ephemeral-volume-pod')


def ports_pod():
    return load_pod('ports-pod')


def container_net_pod():
    return load_pod('container-net-pod')


def container_bridge_pod():
    return load_pod('container-bridge-pod')

def persistent_volume_pod():
    return load_pod('persistent-volume-pod')

def resident_docker_pod():
    return load_pod('resident-docker-pod')
