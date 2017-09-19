"""Marathon acceptance tests for DC/OS."""

import common
import pytest
import shakedown
import time

from datetime import timedelta
from dcos import packagemanager, marathon, cosmos


PACKAGE_NAME = 'marathon'
SERVICE_NAME = 'marathon-user'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def teardown_function(function):
    uninstall('test-marathon')


def setup_module(module):
    uninstall(SERVICE_NAME)
    common.cluster_info()


def teardown_module(module):
    uninstall(SERVICE_NAME)


@pytest.mark.skipif("shakedown.ee_version() == 'strict'", reason="MoM doesn't work on a strict cluster")
def test_install_marathon():
    """Install the Marathon package for DC/OS.
    """

    # Install
    shakedown.install_package_and_wait(PACKAGE_NAME)
    assert shakedown.package_installed(PACKAGE_NAME), 'Package failed to install'

    end_time = time.time() + WAIT_TIME_IN_SECS
    found = False
    while time.time() < end_time:
        found = shakedown.get_service(PACKAGE_NAME) is not None
        if found and shakedown.service_healthy(SERVICE_NAME):
            break
        time.sleep(1)

    assert found, 'Service did not register with DCOS'
    shakedown.deployment_wait()

    # Uninstall
    uninstall('marathon-user')
    shakedown.deployment_wait()

    # Reinstall
    shakedown.install_package_and_wait(PACKAGE_NAME)
    assert shakedown.package_installed(PACKAGE_NAME), 'Package failed to reinstall'


@pytest.mark.skipif("shakedown.ee_version() == 'strict'", reason="MoM doesn't work on a strict cluster")
def test_custom_service_name():
    """  Install MoM with a custom service name.
    """
    cosmos_pm = packagemanager.PackageManager(cosmos.get_cosmos_url())
    pkg = cosmos_pm.get_package_version('marathon', None)
    options = {
        'service': {'name': "test-marathon"}
    }
    shakedown.install_package('marathon', options_json=options)
    shakedown.deployment_wait()

    assert shakedown.wait_for_service_endpoint('test-marathon')


@pytest.fixture(
    params=[
        pytest.mark.skipif("shakedown.required_private_agents(4) or shakedown.ee_version() == 'strict'")('cassandra')
    ])
def package(request):
    package_name = request.param
    yield package_name
    try:
        shakedown.uninstall_package_and_wait(package_name)
        shakedown.delete_persistent_data(
            '{}-role'.format(package_name),
            'dcos-service-{}'.format(package_name))
    except Exception as e:
        # cleanup does NOT fail the test
        print(e)


def test_install_universe_package(package):
    """ Marathon is responsible for installing packages from the universe.
        This test confirms that several packages are installed into a healty state.
    """

    shakedown.install_package_and_wait(package)
    assert shakedown.package_installed(package), 'Package failed to install'

    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds())
    assert shakedown.service_healthy(package)


@pytest.fixture(
    params=[
        'neo4j',
    ])
def neo_package(request):
    package_name = request.param
    yield package_name
    try:
        shakedown.uninstall_package_and_data(package_name, 'neo4j/core')
    except Exception as e:
        # cleanup does NOT fail the test
        print(e)


@shakedown.private_agents(2)
def test_neo4j_universe_package_install(neo_package):
    """ Neo4j used to be 1 of the universe packages tested above, largely
        because there was a bug in marathon for a short period of time
        which was realized through neo4j.  However neo4j is so strongly different
        that we can't test it like the other services.  It is NOT a framework
        so framework health checks do not work with neo4j.
    """
    package = neo_package
    shakedown.install_package(package)
    shakedown.deployment_wait(timeout=timedelta(minutes=5).total_seconds(), app_id='neo4j/core')

    assert shakedown.package_installed(package), 'Package failed to install'

    marathon_client = marathon.create_client()
    tasks = marathon_client.get_tasks('neo4j/core')

    for task in tasks:
        assert task['healthCheckResults'][0]['lastSuccess'] is not None, 'Healthcheck was not successful'
        assert task['healthCheckResults'][0]['consecutiveFailures'] == 0, 'Healthcheck had consecutive failures'


def uninstall(service, package=PACKAGE_NAME):
    try:
        task = shakedown.get_service_task(package, service)
        if task is not None:
            cosmos_pm = packagemanager.PackageManager(cosmos.get_cosmos_url())
            cosmos_pm.uninstall_app(package, True, service)
            shakedown.deployment_wait()
            assert shakedown.wait_for_service_endpoint_removal('test-marathon')
            shakedown.delete_zk_node('/universe/{}'.format(service))

    except Exception as e:
        pass
