import requests
import json
import time
import subprocess

from shakedown import *
from dcos import config
from six.moves import urllib
from common import *

instances_results = []
group_results = []
count_results = []

"""
Scale tests root marathons on DCOS.
"""


def setup_function(function):
    delete_all_apps_wait()


def test_apps_instances_1():
    time = scale_apps(1, 1)
    instances_results.append(time)


def test_apps_instances_10():
    time = scale_apps(1, 10)
    instances_results.append(time)


def test_apps_instances_100():
    time = scale_apps(1, 100)
    instances_results.append(time)


def test_apps_instances_500():
    time = scale_apps(1, 500)
    instances_results.append(time)


def test_apps_instances_1000():
    time = scale_apps(1, 1000)
    instances_results.append(time)


def test_apps_count_1():
    time = scale_apps(1, 1)
    count_results.append(time)


def test_apps_count_10():
    time = scale_apps(10, 1)
    count_results.append(time)


def test_apps_count_100():
    time = scale_apps(100, 1)
    count_results.append(time)


def test_apps_count_500():
    time = scale_apps(500, 1)
    count_results.append(time)


def test_apps_count_1000():
    time = scale_apps(1000, 1)
    count_results.append(time)


def test_groups_instances_1():
    time = scale_groups(1)
    group_results.append(time)


def test_groups_instances_10():
    time = scale_groups(10)
    group_results.append(time)


def test_groups_instances_100():
    time = scale_groups(100)
    group_results.append(time)


def test_groups_instances_500():
    time = scale_groups(500)
    group_results.append(time)


def test_groups_instances_1000():
    time = scale_groups(1000)
    group_results.append(time)


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    cluster_info()
    agents = get_private_agents()
    if len(agents) < 1:
        assert False, "Incorrect Agent count"


def teardown_module(module):
    print("instance test: {}".format(instances_results))
    print("count test: {}".format(count_results))
    print("group test: {}".format(group_results))
