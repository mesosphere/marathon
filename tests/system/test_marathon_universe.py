"""Marathon acceptance tests for DC/OS."""

import pytest

from dcos import (cosmospackage, subcommand)
from dcoscli.package.main import get_cosmos_url

from common import *
from shakedown import *

PACKAGE_NAME = 'marathon'
SERVICE_NAME = 'marathon-user'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


@pytest.mark.sanity
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
    cosmos = cosmospackage.Cosmos(get_cosmos_url())
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
    cosmos = cosmospackage.Cosmos(get_cosmos_url())
    pkg = cosmos.get_package_version('marathon', None)
    options = {
        'service': {'name': "test-marathon"}
    }
    install_package('marathon', options_json=options)
    deployment_wait()

    assert wait_for_service_endpoint('test-marathon')
    cosmos.uninstall_app('marathon', True, 'test-marathon')
    deployment_wait()
    assert wait_for_service_endpoint_removal('test-marathon')

    delete_zk_node('/universe/test-marathon')


def setup_module(module):
    if is_mom_installed():
        try:
            uninstall_package_and_wait(PACKAGE_NAME)
            delete_zk_node('/universe/marathon-user')

        except Exception as e:
            pass
    deployment_wait()


def teardown_module(module):
    # pytest teardown do not seem to be working
    uninstall('marathon-user')
    uninstall('test-user')
    run_command_on_master("docker run mesosphere/janitor /janitor.py -z universe/marathon-user")


def uninstall(service, package='marathon'):
    try:
      task = get_service_task(package, service)
      if task is not None:
          cosmos = cosmospackage.Cosmos(get_cosmos_url())
          cosmos.uninstall_app(package, True, service)
          deployment_wait()
          delete_zk_node('/universe/{}'.format(service))

    except Exception as e:
        pass
