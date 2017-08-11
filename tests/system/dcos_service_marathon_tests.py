"""Tests for root marathon specific to frameworks and readinessChecks """

import apps
import time
import shakedown

from datetime import timedelta
from dcos import marathon
from dcos.errors import DCOSUnprocessableException


def test_deploy_custom_framework():
    """Launches an app that has necessary elements to create a service endpoint in DCOS.
       This test confirms that the endpoint is created by the root Marathon.
    """

    client = marathon.create_client()
    client.add_app(apps.fake_framework())
    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())

    assert shakedown.wait_for_service_endpoint('pyfw', timedelta(minutes=5).total_seconds()), \
        "The framework has not showed up"


def test_framework_readiness_time_check():
    """Tests that an app is being in deployment until the readiness check is done."""

    fw = apps.fake_framework()
    readiness_time = 30
    fw['readinessChecks'][0]['intervalSeconds'] = readiness_time

    client = marathon.create_client()
    deployment_id = client.add_app(fw)

    time.sleep(readiness_time - 10)  # not yet.. still deploying
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False, \
        "The application is read"

    time.sleep(readiness_time + 1)
    assert client.get_deployment(deployment_id) is None, "The application is still being deployed"


def test_framework_rollback_before_ready():
    """Tests the rollback of an app that didn't complete readiness."""

    fw = apps.fake_framework()
    readiness_time = 30
    fw['readinessChecks'][0]['intervalSeconds'] = readiness_time

    client = marathon.create_client()
    deployment_id = client.add_app(fw)

    # 2 secs later it is still being deployed
    time.sleep(2)
    deployment = client.get_deployment(deployment_id)
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False, \
        "The application is ready, but it should not be"

    client.rollback_deployment(deployment_id)
    # normally deployment would take another 28 secs

    assert client.get_deployment(deployment_id) is None, "The application is still being deployed"


def test_framework_has_single_instance():
    """Verifies that Marathon honors the maximum number of instances in cases of frameworks,
       which cannot be greater than 1.
    """

    fw = apps.fake_framework()
    fw['instances'] = 2

    client = marathon.create_client()
    try:
        client.add_app(fw)
    except DCOSUnprocessableException as e:
        assert e.status() == 422, "HTTP status code {} is NOT 422".format(e.status())
    else:
        assert False, "Exception was expected"


def test_framework_never_deploys_due_to_bad_readiness_check():
    """Tests a poor readiness check."""

    fw = apps.fake_framework()
    fw['readinessChecks'][0]['path'] = '/bad-path'

    client = marathon.create_client()
    deployment_id = client.add_app(fw)
    time.sleep(60)
    deployment = client.get_deployment(deployment_id)

    assert deployment is not None, "The deployment finished, but it should not"
    assert deployment['currentActions'][0]['readinessCheckResults'][0]['ready'] is False, \
        "The application is ready, but it is expected not to be"
