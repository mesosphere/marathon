from shakedown import *
from dcos import config
from six.moves import urllib
from utils import get_resource

import requests
import json
import time
import subprocess

instances_results = []
count_results = []

"""
    to launch: shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline test_pod_scale.py
"""

def pod (id=1, instance=1):
    data = get_resource("pod-scale.json")
    data['id'] = "/" + str(id)
    data['scaling']['instances'] = instance
    return data


def pod_time_deployment(test=""):
    global time_series
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    while deployment_count > 0:
        time.sleep(1)
        deployments =  client.get_deployments()
        deployment_count = len(deployments)

    end = time.time()
    elapse = round(end-start,3)
    if "undeploy" not in test:
        print("Test ("+ test + ") time: " +
            str(elapse) + " secs")

    return elapse

def launch_pods(count=1, instances=1):
    client = marathon.create_client()
    for num in range(1, count + 1):
        client.add_pod(pod(num, instances))


def test_pod_instances_1():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(1, 1)
    instances_results.append(time)

def test_pod_instances_10():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(1, 10)
    instances_results.append(time)

def test_pod_instances_100():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(1, 100)
    instances_results.append(time)

def test_pod_instances_500():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(1, 500)
    instances_results.append(time)


def test_pod_count_1():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(1, 1)
    count_results.append(time)

def test_pod_count_10():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(10, 1)
    count_results.append(time)

def test_pod_count_100():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(100, 1)
    count_results.append(time)

def test_pod_count_500():
    client = marathon.create_client()
    delete_all_pods()
    pod_time_deployment("undeploy")

    time = scale_pods(500, 1)
    count_results.append(time)

def scale_pods(pod_count=1, instances=1):
    test = "scaling pods: " + str(pod_count) + " instances: " + str(instances)
    launch_pods(pod_count, instances)
    time = pod_time_deployment(test)
    delete_all_pods()
    pod_time_deployment("undeploy")
    return time

def delete_all_pods():
    client = marathon.create_client()
    pods = client.list_pod()
    for pod in pods:
        client.remove_pod(pod['id'], True)

def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))

def teardown_module(module):
    print("instance test: {}".format(instances_results))
    print("count test: {}".format(count_results))
