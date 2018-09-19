import json
import logging
import pkg_resources
import requests

from six.moves import urllib

from ..clients.authentication import dcos_acs_token, DCOSAcsAuth

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 5


def create_client(url, timeout=DEFAULT_TIMEOUT, auth_token=None):
    return RpcClient(url, timeout, auth_token)


def load_error_json_schema():
    """Reads and parses Marathon error response JSON schema from file

    :returns: the parsed JSON schema
    :rtype: dict
    """
    schema_path = 'data/marathon/error.schema.json'
    schema_bytes = pkg_resources.resource_string('dcos', schema_path)
    return json.loads(schema_bytes.decode('utf-8'))


class RpcClient(object):
    """Convenience class for making requests against a common RPC API.

    For example, it ensures the same base URL is used for all requests. This
    class is also useful as a target for mocks in unit tests, because it
    presents a minimal, application-focused interface.

    :param base_url: the URL prefix to use for all requests
    :type base_url: str
    :param timeout: number of seconds to wait for a response
    :type timeout: float
    :param auth_token: the DC/OS authentication token.
    :type auth_token: str
    """

    def __init__(self, base_url, timeout=None, auth_token=None):
        if not base_url.endswith('/'):
            base_url += '/'
        self._base_url = base_url
        self.session = requests.Session()
        self.session.auth = DCOSAcsAuth(auth_token or dcos_acs_token())
        self.session.timeout = timeout or DEFAULT_TIMEOUT

    def url(self, path):
        return urllib.parse.urljoin(self._base_url, path)
