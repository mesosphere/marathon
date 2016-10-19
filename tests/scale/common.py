import time
from shakedown import *


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


def delete_all_apps(client):
    apps = client.get_apps()
    for app in apps:
        if app['id'] == '/marathon-user':
            print('WARNING: marathon-user installed')
        else:
            client.remove_app(app['id'], True)


def time_deployment(client, test=""):

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


def delete_group(client, group="test"):
    client.remove_group(group, True)


def delete_group_and_wait(client, group="test"):
    delete_group(client, group)
    time_deployment(client, "undeploy")


def launch_apps(client, count=1, instances=1):
    for num in range(1, count + 1):
        client.add_app(app(num, instances))


def launch_group(client, instances=1):
    client.create_group(group(instances))


def delete_all_apps_wait(client):
    delete_all_apps(client)
    time_deployment(client, "undeploy")


def scale_apps(client, count=1, instances=1):
    test = "scaling apps: " + str(count) + " instances " + str(instances)
    launch_apps(client, count, instances)
    time = time_deployment(client, test)
    delete_all_apps_wait(client)
    return time


def scale_groups(client, instances=2):
    test = "group test count: " + str(instances)
    launch_group(client, instances)
    time = time_deployment(client, test)
    delete_group_and_wait(client, "test")
    return time
