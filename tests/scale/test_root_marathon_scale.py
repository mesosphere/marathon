import requests
import json
import time
import subprocess

from shakedown import *
from dcos import config
from six.moves import urllib
from common import *

client = marathon.create_client()
instances_results = []
group_results = []

"""
Scale tests root marathons on DCOS.
"""


def setup_function(function):
    delete_all_apps_wait(client)


def test_apps_instances_1():
    time = scale_apps(client, 1, 1)
    instances_results.append(time)


def test_apps_instances_10():
    time = scale_apps(client, 1, 10)
    instances_results.append(time)


def test_apps_instances_100():
    time = scale_apps(client, 1, 100)
    instances_results.append(time)


def test_apps_instances_500():
    time = scale_apps(client, 1, 500)
    instances_results.append(time)


def test_apps_instances_1000():
    time = scale_apps(client, 1, 1000)
    instances_results.append(time)


def test_groups_instances_1():
    time = scale_groups(client, 1)
    group_results.append(time)


def test_groups_instances_10():
    time = scale_groups(client, 10)
    group_results.append(time)


def test_groups_instances_100():
    time = scale_groups(client, 100)
    group_results.append(time)


def test_groups_instances_500():
    time = scale_groups(client, 500)
    group_results.append(time)


def test_groups_instances_1000():
    time = scale_groups(client, 1000)
    group_results.append(time)


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    if len(agents) < 1:
        assert False, "Incorrect Agent count"
    global client
    client = marathon.create_client()


def teardown_module(module):
    print("instance test: {}".format(instances_results))
    print("group test: {}".format(group_results))
