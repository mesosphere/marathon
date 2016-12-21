import pytest
import time
import uuid

from common import *
from shakedown import *
from utils import *
from dcos import *


def test_framework_unavailable_on_mom():
    if wait_for_service_url('pyfw'):
        client = marathon.create_client()
        client.remove_app('python-http')
        deployment_wait()
        wait_for_service_endpoint_removal('pyfw')

    with marathon_on_marathon():
        delete_all_apps_wait()
        client = marathon.create_client()
        client.add_app(fake_framework_app())
        deployment_wait()

    assert wait_for_service_url('pyfw', 15) == False


def teardown_function(test_framework_unavailable_on_mom):
    with marathon_on_marathon():
        delete_all_apps_wait()


def test_deploy_custom_framework():
    client = marathon.create_client()
    client.add_app(fake_framework_app())
    deployment_wait()

    assert wait_for_service_url('pyfw')

def remove_pyfw():
    client = marathon.create_client()
    client.remove_app('python-http', True)
    deployment_wait()

def teardown_function(test_deploy_custom_framework):
    remove_pyfw()

def test_readiness_time_check():
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    fw['readinessChecks'][0]['interval'] = 30
    deployment_id = client.add_app(fw)
    time.sleep(20)
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] == False
    #time after 30 secs
    time.sleep(12)
    assert client.get_deployment(deployment_id) is None

def teardown_function(test_readiness_time_check):
    remove_pyfw()

def test_readiness_test_timeout():
    client = marathon.create_client()
    fw = fake_framework_app()
    fw['readinessChecks'][0]['path'] = '/bad-path'
    deployment_id = client.add_app(fw)
    time.sleep(60)
    deployment = client.get_deployment(deployment_id)
    assert deployment is not None
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] == False

def teardown_function(test_readiness_test_timeout):
    remove_pyfw()


def teardown_function(test_readiness_test_timeout):
    client = marathon.create_client()
    deployments = client.get_deployments()
    if deployments is None:
        return

    for deployment in deployments:
        client.remove_app(deployment['affectedApps'][0], True)



def setup_module(module):
    ensure_mom()
    cluster_info()
