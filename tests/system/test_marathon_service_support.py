import pytest
import time
import uuid

from common import *
from shakedown import *
from utils import *
from dcos import *


@pytest.mark.usefixtures("mom_needed")
def test_framework_unavailable_on_mom():

    if service_available_predicate('pyfw'):
        client = marathon.create_client()
        client.remove_app('python-http', True)
        deployment_wait()
        wait_for_service_endpoint_removal('pyfw')

    with marathon_on_marathon():
        delete_all_apps_wait()
        client = marathon.create_client()
        client.add_app(fake_framework_app())
        deployment_wait()

    try:
        wait_for_service_endpoint('pyfw', 15)
        assert False, 'MoM shoud NOT create a service endpoint'
    except:
        assert True
        pass


def test_deploy_custom_framework():
    client = marathon.create_client()
    client.add_app(fake_framework_app())
    deployment_wait()

    assert wait_for_service_endpoint('pyfw')


def remove_pyfw():
    client = marathon.create_client()
    try:
        client.remove_app('python-http', True)
        deployment_wait()
    except:
        pass


def test_readiness_time_check():
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    fw['readinessChecks'][0]['intervalSeconds'] = 30
    deployment_id = client.add_app(fw)
    time.sleep(20)
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False
    # time after 30 secs
    time.sleep(12)
    assert client.get_deployment(deployment_id) is None


def test_rollback_before_ready():
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    fw['readinessChecks'][0]['intervalSeconds'] = 30
    deployment_id = client.add_app(fw)

    # 2 secs later it is still deploying
    time.sleep(2)
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False

    client.rollback_deployment(deployment_id)
    # normally deployment would take another 28 secs

    assert client.get_deployment(deployment_id) is None


def test_single_instance():
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    fw['instances'] = 2

    try:
        deployment_id = client.add_app(fw)
    except DCOSException as e:
        assert e.status() == 422
    else:
        assert False, "Exception expected for number of instances requested"


def teardown_function(function):
    remove_pyfw()


@pytest.mark.usefixtures("remove_undeployed")
def test_readiness_test_timeout():
    client = marathon.create_client()
    fw = fake_framework_app()
    fw['readinessChecks'][0]['path'] = '/bad-path'
    deployment_id = client.add_app(fw)
    time.sleep(60)
    deployment = client.get_deployment(deployment_id)
    assert deployment is not None
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False


def setup_module(module):
    cluster_info()
