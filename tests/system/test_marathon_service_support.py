"""Tests for root marathon specific to frameworks and readinessChecks """
import pytest
import time
import uuid
import shakedown

from common import cluster_info, delete_all_apps_wait, fake_framework_app, mom_needed, remove_undeployed
from utils import marathon_on_marathon
from dcos import marathon
from dcos.errors import DCOSException


@pytest.mark.usefixtures("mom_needed")
def test_framework_unavailable_on_mom():
    """ Launches an app that has elements necessary to create a service endpoint in DCOS.
        This test confirms that the endpoint is not created when launched with MoM.
    """
    if shakedown.service_available_predicate('pyfw'):
        client = marathon.create_client()
        client.remove_app('python-http', True)
        shakedown.deployment_wait()
        shakedown.wait_for_service_endpoint_removal('pyfw')

    with marathon_on_marathon():
        delete_all_apps_wait()
        client = marathon.create_client()
        client.add_app(fake_framework_app())
        shakedown.deployment_wait()

    try:
        shakedown.wait_for_service_endpoint('pyfw', 15)
        assert False, 'MoM shoud NOT create a service endpoint'
    except:
        assert True
        pass


def test_deploy_custom_framework():
    """ Launches an app that has elements necessary to create a service endpoint in DCOS.
        This test confirms that the endpoint is created from the root marathon.
    """

    client = marathon.create_client()
    client.add_app(fake_framework_app())
    shakedown.deployment_wait()

    assert shakedown.wait_for_service_endpoint('pyfw')


def remove_pyfw():
    client = marathon.create_client()
    try:
        client.remove_app('python-http', True)
        shakedown.deployment_wait()
    except:
        pass


def test_readiness_time_check():
    """ Test that an app is still in deployment until the readiness check.
    """
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    readiness_time = 30
    fw['readinessChecks'][0]['intervalSeconds'] = readiness_time
    deployment_id = client.add_app(fw)
    time.sleep(readiness_time - 10)  # not yet.. still deploying
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False

    # time after 30 secs
    time.sleep(readiness_time + 1)
    assert client.get_deployment(deployment_id) is None


def test_rollback_before_ready():
    """ Tests the rollback of an app that didn't complete readiness.
    """
    client = marathon.create_client()
    fw = fake_framework_app()
    # testing 30 sec interval
    readiness_time = 30
    fw['readinessChecks'][0]['intervalSeconds'] = readiness_time
    deployment_id = client.add_app(fw)

    # 2 secs later it is still deploying
    time.sleep(2)
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False

    client.rollback_deployment(deployment_id)
    # normally deployment would take another 28 secs

    assert client.get_deployment(deployment_id) is None


def test_single_instance():
    """ Tests to see that marathon honors instance instance apps (such as a framework).
        They do not scale past 1.
    """
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
    """ Tests a poor readiness check.
    """
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
