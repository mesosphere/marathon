import requests
import json
import time
import subprocess

from shakedown import *
from dcos import config
from six.moves import urllib
from common import *

timing_results = []

def time_launch_apps(count=1, instances=1):
    client = marathon.create_client()
    for num in range(1, count + 1):
        start = time.time()
        client.add_app(app(num, instances))
        end = time.time()
        elapse = round(end - start, 3)
        if num % 50 == 0:
            timing_results.append(elapse)

def time_launch_apps_http(count=1, instances=1, timeout=120):

    for num in range(1, count + 1):
        start = time.time()
        try:
            response = http.post(APP_URL,
                     json=app(num, instances),
                     timeout=timeout)

        except DCOSHTTPException as e:
            if e.response.status_code == 404:
                print("404")
            else:
                print(e)
                print("status_code: {}".format(e.response.status_code))

        end = time.time()
        elapse = round(end - start, 3)
        if response.status_code != 201:
            print("http code: {}".format(response.status_code))

        if response.json()['deployments'][0]['id'] is None:
            print("missing deployment")
        if num % 50 == 0:
            timing_results.append(elapse)


def test_counts():
    time_launch_apps(1000)

def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    cluster_info()
    agents = get_private_agents()
    if len(agents) < 1:
        assert False, "Incorrect Agent count"


def teardown_module(module):
    print("timing: {}".format(timing_results))
