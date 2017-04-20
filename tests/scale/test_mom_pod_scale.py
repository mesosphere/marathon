from shakedown import *
from dcos import config
from six.moves import urllib
from utils import get_resource

import requests
import json
import time
from common import *

instances_results = []
instances_teardown = []
count_results = []
count_teardown = []


"""
    to launch: shakedown --dcos-url=$(dcos config show core.dcos_url)
        --ssh-key-file=~/.ssh/default.pem --stdout all
        --stdout-inline test_pod_scale.py
"""


def pod(id=1, instance=1, type="4"):
    data = get_resource("pod-{}-containers.json".format(type))
    data['id'] = "/" + str(id)
    data['scaling']['instances'] = instance
    return data


def pod_time_deployment():
    global time_series
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    while deployment_count > 0:
        time.sleep(1)
        deployments = client.get_deployments()
        deployment_count = len(deployments)

    elapse = elapse_time(start)

    return elapse


def launch_pods(count=1, instances=1):
    client = marathon.create_client()
    for num in range(1, count + 1):
        client.add_pod(pod(num, instances))


def test_pod_instances_1():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 1, instances_results, instances_teardown)


def test_pod_instances_10():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 10, instances_results, instances_teardown)


def test_pod_instances_100():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 100, instances_results, instances_teardown)


def test_pod_instances_500():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 500, instances_results, instances_teardown)


def test_pod_instances_1000():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 1000, instances_results, instances_teardown)


def test_pod_instances_5000():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 5000, instances_results, instances_teardown)


def test_pod_count_1():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1, 1, count_results, count_teardown)


def test_pod_count_10():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(10, 1, count_results, count_teardown)


def test_pod_count_100():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(100, 1, count_results, count_teardown)


def test_pod_count_500():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(500, 1, count_results, count_teardown)


def test_pod_count_1000():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(1000, 1, count_results, count_teardown)


def test_pod_count_5000():
    with shakedown.marathon_on_marathon()::
        _test_pod_scale(5000, 1, count_results, count_teardown)


def _test_pod_scale(pod_count, instances, test_results, teardown_results):
    test = "scaling pods: " + str(pod_count) + " instances: " + str(instances)
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment()
    test_time, teardown_time = scale_pods(pod_count, instances)
    print("{} test time: {}".format(test, test_time))
    print("{} teardown time: {}".format(test, teardown_time))

    test_results.append(test_time)
    teardown_results.append(teardown_time)


def scale_pods(pod_count=1, instances=1):
    start = time.time()
    launch_pods(pod_count, instances)
    deploy_time = pod_time_deployment()
    test_end = time.time()
    delete_all_pods()
    pod_time_deployment()
    delete_time = elapse_time(test_end)
    return elapse_time(start, test_end), delete_time


def delete_all_pods():
    client = marathon.create_client()
    pods = client.list_pod()
    print("deleting {} pods".format(len(pods)))
    client.remove_group("/", True)
    pod_time_deployment()


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    with shakedown.marathon_on_marathon()::
        client = marathon.create_client()
        about = client.get_about()
        print("marathon version: {}".format(about.get("version")))
    prefetch_docker_images_on_all_nodes()


def teardown_module(module):
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    with shakedown.marathon_on_marathon()::
        client = marathon.create_client()
        about = client.get_about()
        print("marathon version: {}".format(about.get("version")))
        delete_all_pods()
    print("instance test: {}".format(instances_results))
    print("instance teardown: {}".format(instances_teardown))
    print("count test: {}".format(count_results))
    print("count teardown: {}".format(count_teardown))


def prefetch_docker_images_on_all_nodes():
    with shakedown.marathon_on_marathon()::
        agents = get_private_agents()
        data = get_resource("pod-2-containers.json")
        data['constraints'] = unique_host_constraint()
        data['scaling']['instances'] = len(agents)
        client = marathon.create_client()
        client.add_pod(data)
        time_deployment("undeploy")
        delete_all_pods()
