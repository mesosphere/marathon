"""Marathon acceptance tests for DC/OS."""

from shakedown import *

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def test_install_marathon():
    """Install the Marathon package for DC/OS.
    """
    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to install'

    end_time = time.time() + WAIT_TIME_IN_SECS
    found = False
    while time.time() < end_time:
        found = get_service(PACKAGE_NAME) is not None
        if found and service_healthy(PACKAGE_NAME):
            break
        time.sleep(1)

    assert found, 'Service did not register with DCOS'


def test_uninstall_marathon():
    """Uninstall the Marathon package for DC/OS.
    """
    uninstall_package_and_wait(PACKAGE_NAME)
    assert not package_installed(PACKAGE_NAME), 'Package failed to uninstall'
