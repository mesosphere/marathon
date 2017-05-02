"""
Authenication and Authorization tests which require DC/OS Enterprise.
Currently test against root marathon.  Assume we will want to test these
against MoM EE
"""
import common
import dcos
import pytest
import shakedown

from urllib.parse import urljoin
from dcos import marathon

from shakedown import credentials, ee_version


@pytest.mark.skipif("ee_version() is None")
@pytest.mark.usefixtures('credentials')
def test_non_authenicated_user():
    with shakedown.no_user():
        with pytest.raises(dcos.errors.DCOSAuthenticationException) as exec_info:
            response = dcos.http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exc_info.value
            assert str(error) == "Authentication failed. Please run `dcos auth login`"


@pytest.mark.skipif("ee_version() is None")
@pytest.mark.usefixtures('credentials')
def test_non_authorized_user():
    with shakedown.new_dcos_user('kenny', 'kenny'):
        with pytest.raises(dcos.errors.DCOSAuthorizationException) as exec_info:
            response = dcos.http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exc_info.value
            assert str(error) == "You are not authorized to perform this operation"


@pytest.fixture(scope="function")
def billy():
    shakedown.add_user('billy', 'billy')
    shakedown.set_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    shakedown.set_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    yield
    shakedown.remove_user_permission(rid='dcos:adminrouter:service:marathon', uid='billy', action='full')
    shakedown.remove_user_permission(rid='dcos:service:marathon:marathon:services:/', uid='billy', action='full')
    shakedown.remove_user('billy')


@pytest.mark.skipif("ee_version() is None")
@pytest.mark.usefixtures('credentials')
def test_authorized_non_super_user(billy):
    with shakedown.dcos_user('billy', 'billy'):
        client = marathon.create_client()
        len(client.get_apps()) == 0
