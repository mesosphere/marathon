import requests
import json
import time
import subprocess

from shakedown import *
from dcos import config
from six.moves import urllib
from common import *

client = marathon.create_client()
toml_config_o = config.get_config()
instances_results = []
group_results = []


"""
Scale tests MoM marathons on DCOS.  It is expected that
the preferred Marathon is installed before execution and
that the name is 'marathon-user'.
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


def update_marathon_client():
    global client
    global toml_config_o
    toml_config_o = config.get_config()
    dcos_url = config.get_config_val('core.dcos_url', toml_config_o)
    marathon_url = urllib.parse.urljoin(dcos_url, 'service/marathon-user/')
    config.set_val('marathon.url', marathon_url)
    toml_config_m = config.get_config()
    client = marathon.create_client(toml_config_m)


def reset_toml():
    global toml_config_o
    config.save(toml_config_o)


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    if len(agents) < 1:
        assert False, "Incorrect Agent count"
    update_marathon_client()


def teardown_module(module):
    reset_toml()
    print("instance test: {}".format(instances_results))
    print("group test: {}".format(group_results))
