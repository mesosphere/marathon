from shakedown import *

def test_install_package_cli():
    assert not package_installed('dcos-enterprise-cli')
    install_package_and_wait('dcos-enterprise-cli')
    assert package_installed('dcos-enterprise-cli')

def test_uninstall_package_cli():
    assert package_installed('dcos-enterprise-cli')
    uninstall_package_and_wait('dcos-enterprise-cli')
    assert not package_installed('dcos-enterprise-cli')
