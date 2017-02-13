"""Marathon acceptance tests for DC/OS."""

import pytest

from dcos import (packagemanager, subcommand)
from dcos.cosmos import get_cosmos_url

from common import *
from shakedown import *

PACKAGE_NAME = 'marathon'
SERVICE_NAME = 'marathon-user'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def test_install_marathon():
    """Install the Marathon package for DC/OS.
    """

    # Install
    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to install'

    end_time = time.time() + WAIT_TIME_IN_SECS
    found = False
    while time.time() < end_time:
        found = get_service(PACKAGE_NAME) is not None
        if found and service_healthy(SERVICE_NAME):
            break
        time.sleep(1)

    assert found, 'Service did not register with DCOS'
    deployment_wait()

    # Uninstall
    uninstall('marathon-user')
    deployment_wait()

    # Reinstall
    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to reinstall'
    #
    try:
        install_package(PACKAGE_NAME)
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
    install_package('marathon', options_json=options)
    deployment_wait()

    assert wait_for_service_endpoint('test-marathon')


def teardown_function(function):
    uninstall('test-marathon')


def setup_module(module):
    uninstall(SERVICE_NAME)


def teardown_module(module):
    uninstall(SERVICE_NAME)


def uninstall(service, package=PACKAGE_NAME):
    try:
        task = get_service_task(package, service)
        if task is not None:
            cosmos = packagemanager.PackageManager(get_cosmos_url())
            cosmos.uninstall_app(package, True, service)
            deployment_wait()
            assert wait_for_service_endpoint_removal('test-marathon')
            delete_zk_node('/universe/{}'.format(service))

    except Exception as e:
        pass
