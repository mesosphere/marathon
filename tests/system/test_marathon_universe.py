"""Marathon acceptance tests for DC/OS."""

import common
import pytest
import retrying
import logging

from shakedown.clients import packagemanager, cosmos, dcos_service_url
from shakedown.dcos.agent import required_private_agents # NOQA F401
from shakedown.dcos.cluster import ee_version # NOQA F401
from shakedown.dcos.marathon import deployment_wait
from shakedown.dcos.package import (install_package, install_package_and_wait, package_installed,
                                    uninstall_package_and_wait)
from shakedown.dcos.service import (delete_persistent_data, delete_zk_node, get_service, get_service_task,
                                    service_healthy)


logger = logging.getLogger(__name__)

PACKAGE_NAME = 'marathon'
SERVICE_NAME = 'marathon-user'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def teardown_function(function):
    uninstall('test-marathon')


def setup_module(module):
    uninstall(SERVICE_NAME)
    common.cluster_info()


def teardown_module(module):
    uninstall(SERVICE_NAME)


@pytest.mark.skipif("ee_version() == 'strict'", reason="MoM doesn't work on a strict cluster")
def test_install_marathon():
    """Install the Marathon package for DC/OS.
    """

    # Install
    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def install_marathon():
        install_package_and_wait(PACKAGE_NAME)

    install_marathon()
    assert package_installed(PACKAGE_NAME), 'Package failed to install'

    # 5000ms = 5 seconds, 5 seconds * 60 attempts = 300 seconds = WAIT_TIME_IN_SECS
    @retrying.retry(wait_fixed=5000, stop_max_attempt_number=60, retry_on_exception=common.ignore_exception)
    def assert_service_registration(package, service):
        found = get_service(package) is not None
        assert found and service_healthy(service), f"Service {package} did not register with DCOS" # NOQA E999

    assert_service_registration(PACKAGE_NAME, SERVICE_NAME)
    deployment_wait(service_id=SERVICE_NAME)

    # Uninstall
    uninstall('marathon-user')
    deployment_wait(service_id=SERVICE_NAME)

    # Reinstall
    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to reinstall'


@pytest.mark.skipif("ee_version() == 'strict'", reason="MoM doesn't work on a strict cluster")
def test_custom_service_name():
    """  Install MoM with a custom service name.
    """
    cosmos_pm = packagemanager.PackageManager(cosmos.get_cosmos_url())
    cosmos_pm.get_package_version('marathon', None)
    options = {
        'service': {'name': "test-marathon"}
    }
    install_package('marathon', options_json=options)
    deployment_wait(service_id=options["service"]["name"], max_attempts=300)

    common.wait_for_service_endpoint('test-marathon', timeout_sec=300, path="ping")


@pytest.fixture(
    params=[
        pytest.mark.skipif("required_private_agents(4) or ee_version() == 'strict'")('cassandra')
    ])
def package(request):
    package_name = request.param
    yield package_name
    try:
        uninstall_package_and_wait(package_name)
        delete_persistent_data('{}-role'.format(package_name), 'dcos-service-{}'.format(package_name))
    except Exception as e:
        # cleanup does NOT fail the test
        logger.exception('Faild to uninstall {} package'.format(package_name))


def test_install_universe_package(package):
    """ Marathon is responsible for installing packages from the universe.
        This test confirms that several packages are installed into a healty state.
    """

    install_package_and_wait(package)
    assert package_installed(package), 'Package failed to install'

    deployment_wait(max_attempts=300)
    assert service_healthy(package)


def uninstall(service, package=PACKAGE_NAME):
    try:
        task = get_service_task(package, service)
        if task is not None:
            cosmos_pm = packagemanager.PackageManager(cosmos.get_cosmos_url())
            cosmos_pm.uninstall_app(package, True, service)
            deployment_wait()
            assert common.wait_for_service_endpoint_removal('test-marathon')
            delete_zk_node('/universe/{}'.format(service))

    except Exception:
        pass
