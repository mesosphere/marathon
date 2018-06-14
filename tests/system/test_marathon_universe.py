"""Marathon acceptance tests for DC/OS."""

import common
import pytest
import retrying
import shakedown

from datetime import timedelta
from dcos import packagemanager, cosmos


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
    @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
    def install_marathon():
        shakedown.install_package_and_wait(PACKAGE_NAME)

    install_marathon()
    assert shakedown.package_installed(PACKAGE_NAME), 'Package failed to install'

    # 5000ms = 5 seconds, 5 seconds * 60 attempts = 300 seconds = WAIT_TIME_IN_SECS
    @retrying.retry(wait_fixed=5000, stop_max_attempt_number=60, retry_on_exception=common.ignore_exception)
    def assert_service_registration(package, service):
        found = shakedown.get_service(package) is not None
        assert found and shakedown.service_healthy(service), f"Service {package} did not register with DCOS" # NOQA E999

    assert_service_registration(PACKAGE_NAME, SERVICE_NAME)
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
    cosmos_pm.get_package_version('marathon', None)
    options = {
        'service': {'name': "test-marathon"}
    }
    shakedown.install_package('marathon', options_json=options)
    shakedown.deployment_wait()

    assert common.wait_for_service_endpoint('test-marathon')


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


def uninstall(service, package=PACKAGE_NAME):
    try:
        task = shakedown.get_service_task(package, service)
        if task is not None:
            cosmos_pm = packagemanager.PackageManager(cosmos.get_cosmos_url())
            cosmos_pm.uninstall_app(package, True, service)
            shakedown.deployment_wait()
            assert common.wait_for_service_endpoint_removal('test-marathon')
            shakedown.delete_zk_node('/universe/{}'.format(service))

    except Exception:
        pass
