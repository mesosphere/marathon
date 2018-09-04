from shakedown import *


def test_install_package_jenkins():
    if not package_installed('jenkins'):
        install_package_and_wait('jenkins')
