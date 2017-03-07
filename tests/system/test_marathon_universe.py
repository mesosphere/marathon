"""Marathon acceptance tests for DC/OS."""

import pytest
import shakedown
import time

from dcos import (packagemanager, subcommand)
from dcos.cosmos import get_cosmos_url

from common import cluster_info

PACKAGE_NAME = 'marathon'
SERVICE_NAME = 'marathon-user'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


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
    #
    try:
        shakedown.install_package(PACKAGE_NAME)
    except Exception as e:
        pass
    else:
        # Exception is not raised -> exit code was 0
        assert False, "Error: CLI returns 0 when asked to install Marathon"


def test_custom_service_name():
    """  Install MoM with a custom service name.
    """
    cosmos = packagemanager.PackageManager(get_cosmos_url())
    pkg = cosmos.get_package_version('marathon', None)
    options = {
        'service': {'name': "test-marathon"}
    }
    shakedown.install_package('marathon', options_json=options)
    shakedown.deployment_wait()

    assert shakedown.wait_for_service_endpoint('test-marathon')


def teardown_function(function):
    uninstall('test-marathon')


def setup_module(module):
    uninstall(SERVICE_NAME)
    cluster_info()


def teardown_module(module):
    uninstall(SERVICE_NAME)


def uninstall(service, package=PACKAGE_NAME):
    try:
        task = shakedown.get_service_task(package, service)
        if task is not None:
            cosmos = packagemanager.PackageManager(get_cosmos_url())
            cosmos.uninstall_app(package, True, service)
            shakedown.deployment_wait()
            assert wait_for_service_endpoint_removal('test-marathon')
            shakedown.delete_zk_node('/universe/{}'.format(service))

    except Exception as e:
        pass
