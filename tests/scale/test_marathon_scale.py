import requests
import json
import time
import subprocess

from shakedown import *
from dcos import config
from six.moves import urllib

toml_config_o = config.get_config()
instances_results = []
group_results = []

def test_apps_instances_10():
    delete_all_apps_wait()
    time = scale_apps(1,10)
    instances_results.append(time)

def test_apps_instances_100():
    delete_all_apps_wait()
    time = scale_apps(1,100)
    instances_results.append(time)

def test_apps_instances_1000():
    delete_all_apps_wait()
    scale_apps(1,1000)

def test_apps_instances_3000():
    delete_all_apps_wait()
    scale_apps(1,3000)

def test_groups_instances_2():
    delete_all_apps_wait()
    time = scale_groups(2)
    group_results.append(time)

def test_groups_instances_20():
    delete_all_apps_wait()
    scale_groups(20)

def test_groups_instances_200():
    delete_all_apps_wait()
    scale_groups(200)

def test_groups_instances_2000():
    delete_all_apps_wait()
    scale_groups(2000)

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

def app( id = 1, instances = 1):
    app_json = {
      "id": "",
      "instances":  1,
      "cmd": "sleep 100000000",
      "cpus": 0.01,
      "mem": 1,
      "disk": 0
    }
    if not str(id).startswith("/"):
        id = "/" + str(id)
    app_json['id'] = id
    app_json['instances'] = instances

    return app_json

def group( gcount = 1, instances = 1):

    id = "/test"
    group = {
        "id" : id,
        "apps" : []
    }

    for num in range(1, gcount + 1):
        app_json = app( id + "/" + str(num), instances)
        group['apps'].append(app_json)

    return group

def time_deployment(test=""):

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

def delete_group(group="test"):
    client.remove_group(group, True)

def delete_group_and_wait(group="test"):
    delete_group()
    time_deployment("undeploy")

def launch_apps(count=1, instances=1):
    for num in range(1, count + 1):
        client.add_app(app(num, instances))

def launch_group(instances=1):
    client.create_group(group(instances))

def delete_all_apps():
    apps = client.get_apps()
    for app in apps:
        client.remove_app(app['id'], True)

def delete_all_apps_wait():
    delete_all_apps()
    time_deployment("undeploy")

def scale_apps(count=1, instances=1):
    test = "scaling apps: " + str(count) + " instances " + str(instances)
    launch_apps(count, instances)
    time = time_deployment(test)
    delete_all_apps_wait()
    return time

def scale_groups(instances=2):
    test = "group test count: " + str(instances)
    launch_group(instances)
    time = time_deployment(test)
    delete_group_and_wait("test")
    return time

def scale_apps(app_count=1, instances=1):
    test = "scaling test apps: " + str(app_count) + " instances: " + str(instances)
    launch_apps(app_count, instances)
    time = time_deployment(test)
    delete_all_apps_wait()
    return time

def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    if len(agents)<10:
        assert False, "Incorrect Agent count"
    update_marathon_client()

def teardown_module(module):
    reset_toml()
    print("instance test: {}".format(instances_results))
    print("group test: {}".format(group_results))
