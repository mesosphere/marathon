"""Authentication and Authorization tests against DC/OS Enterprise and root Marathon."""

import pytest
import requests

from shakedown.clients import dcos_url_path, marathon
from shakedown.clients.authentication import DCOSAcsAuth
from shakedown.clients.rpcclient import verify_ssl
from shakedown.dcos.cluster import ee_version # NOQA F401
from shakedown.dcos.security import dcos_user, new_dcos_user


@pytest.mark.skipif("ee_version() is None")
def test_non_authenticated_user():
    response = requests.get(dcos_url_path('service/marathon/v2/apps'), auth=None, verify=verify_ssl())
    assert response.status_code == 401


@pytest.mark.skipif("ee_version() is None")
def test_non_authorized_user():
    with new_dcos_user('kenny', 'kenny') as auth_token:
        auth = DCOSAcsAuth(auth_token)
        response = requests.get(dcos_url_path('service/marathon/v2/apps'), auth=auth, verify=verify_ssl())
        assert response.status_code == 403


# NOTE:  this is a common test file. All test suites which import this common
# set of tests will need to `from fixtures import user_billy` for this fixture to work.
@pytest.mark.skipif("ee_version() is None")
def test_authorized_non_super_user(user_billy):
    with dcos_user('billy', 'billy') as auth_token:
        client = marathon.create_client(auth_token=auth_token)
        assert len(client.get_apps()) == 0
