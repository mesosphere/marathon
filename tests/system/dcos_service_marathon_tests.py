"""Tests for root marathon specific to frameworks and readinessChecks """
import pytest
import time
import uuid
import shakedown

from common import fake_framework_app
from datetime import timedelta
from dcos import marathon
from dcos.errors import DCOSException

from datetime import timedelta

def test_deploy_custom_framework():
    """ Launches an app that has elements necessary to create a service endpoint in DCOS.
        This test confirms that the endpoint is created from the root marathon.
    """

    client = marathon.create_client()
    client.add_app(fake_framework_app())
    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())

    assert shakedown.wait_for_service_endpoint('pyfw', timedelta(minutes=5).total_seconds())


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
