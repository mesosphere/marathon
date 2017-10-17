"""Authentication and Authorization tests against DC/OS Enterprise and root Marathon."""

import dcos
import dcos.errors
import dcos.http
import pytest
import shakedown

from dcos import marathon
from urllib.parse import urljoin


@pytest.mark.skipif("shakedown.ee_version() is None")
def test_non_authenticated_user():
    with shakedown.no_user():
        with pytest.raises(dcos.errors.DCOSAuthenticationException) as exec_info:
            _ = dcos.http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exec_info.value
            assert str(error) == "Authentication failed. Please run `dcos auth login`"


@pytest.mark.skipif("shakedown.ee_version() is None")
def test_non_authorized_user():
    with shakedown.new_dcos_user('kenny', 'kenny'):
        with pytest.raises(dcos.errors.DCOSAuthorizationException) as exec_info:
            _ = dcos.http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exec_info.value
            assert str(error) == "You are not authorized to perform this operation"


@pytest.mark.skipif("shakedown.ee_version() is None")
def test_authorized_non_super_user(user_billy):
    with shakedown.dcos_user('billy', 'billy'):
        client = marathon.create_client()
        assert len(client.get_apps()) == 0
