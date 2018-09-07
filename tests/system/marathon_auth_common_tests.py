"""Authentication and Authorization tests against DC/OS Enterprise and root Marathon."""

import pytest
import shakedown

from shakedown.clients import marathon
from shakedown import errors, http
from urllib.parse import urljoin


@pytest.mark.skipif("shakedown.dcos.cluster.ee_version() is None")
def test_non_authenticated_user():
    with shakedown.no_user():
        with pytest.raises(errors.DCOSAuthenticationException) as exec_info:
            http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exec_info.value
            assert str(error) == "Authentication failed. Please run `dcos auth login`"


@pytest.mark.skipif("shakedown.dcos.cluster.ee_version() is None")
def test_non_authorized_user():
    with shakedown.new_dcos_user('kenny', 'kenny'):
        with pytest.raises(errors.DCOSAuthorizationException) as exec_info:
            http.get(urljoin(shakedown.dcos_url(), 'service/marathon/v2/apps'))
            error = exec_info.value
            assert str(error) == "You are not authorized to perform this operation"


# NOTE:  this is a common test file. All test suites which import this common
# set of tests will need to `from fixtures import user_billy` for this fixture to work.
@pytest.mark.skipif("shakedown.dcos.cluster.ee_version() is None")
def test_authorized_non_super_user(user_billy):
    with shakedown.dcos_user('billy', 'billy'):
        client = marathon.create_client()
        assert len(client.get_apps()) == 0
