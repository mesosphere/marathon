import time

from shakedown import *
from utils import *


def app(id=1, instances=1):
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


def group(gcount=1, instances=1):
    id = "/test"
    group = {
        "id": id,
        "apps": []
    }

    for num in range(1, gcount + 1):
        app_json = app(id + "/" + str(num), instances)
        group['apps'].append(app_json)

    return group



def constraints(name, operator, value=None):
    constraints = [name, operator]
    if value is not None:
      constraints.append(value)
    return [constraints]


def unique_host_constraint():
    return constraints('hostname', 'UNIQUE')


def delete_all_apps():
    client = marathon.create_client()
    client.remove_group("/")
    time_deployment("undeploy")


def time_deployment(test=""):
    client = marathon.create_client()
    start = time.time()
    deployment_count = 1
    while deployment_count > 0:
        time.sleep(1)
        deployments = client.get_deployments()
        deployment_count = len(deployments)

    end = time.time()
    elapse = round(end - start, 3)
    if "undeploy" not in test:
        print("Test (" + test + ") time: " +
              str(elapse) + " secs")
    return elapse


def delete_group(group="test"):
    client = marathon.create_client()
    client.remove_group(group, True)


def delete_group_and_wait(group="test"):
    delete_group(group)
    time_deployment("undeploy")


def launch_apps(count=1, instances=1):
    client = marathon.create_client()
    for num in range(1, count + 1):
        client.add_app(app(num, instances))


def launch_group(instances=1):
    client = marathon.create_client()
    client.create_group(group(instances))


def delete_all_apps_wait():
    delete_all_apps()
    time_deployment("undeploy")


def scale_apps(count=1, instances=1):
    test = "scaling apps: " + str(count) + " instances " + str(instances)

    start = time.time()
    launch_apps(count, instances)
    deploy_time = time_deployment(test)
    delete_all_apps_wait()
    return elapse_time(start)


def scale_groups(instances=2):
    test = "group test count: " + str(instances)
    launch_group(instances)
    time = time_deployment(test)
    delete_group_and_wait("test")
    return time


def elapse_time(start, end=None):
    if end is None:
        end = time.time()
    return round(end-start, 3)


def cluster_info(mom_name='marathon-user'):
    agents = get_private_agents()
    print("agents: {}".format(len(agents)))
    client = marathon.create_client()
    about = client.get_about()
    print("marathon version: {}".format(about.get("version")))
    # see if there is a MoM
    with marathon_on_marathon(mom_name):
        try:
            client = marathon.create_client()
            about = client.get_about()
            print("marathon MoM version: {}".format(about.get("version")))

        except Exception as e:
            print("Marathon MoM not present")
