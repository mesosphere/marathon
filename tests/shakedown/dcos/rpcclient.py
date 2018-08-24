import json

import jsonschema
import pkg_resources

from six.moves import urllib

from dcos import http, util
from dcos.errors import DCOSException, DCOSHTTPException

logger = util.get_logger(__name__)


def create_client(url, timeout):
    return RpcClient(url, timeout)


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
    """

    def __init__(self, base_url, timeout=http.DEFAULT_TIMEOUT):
        if not base_url.endswith('/'):
            base_url += '/'
        self._base_url = base_url
        self._timeout = timeout

    ERROR_JSON_VALIDATOR = jsonschema.Draft4Validator(load_error_json_schema())
    RESOURCE_TYPES = ['app', 'group', 'pod']

    @classmethod
    def response_error_message(cls, status_code, reason, request_method,
                               request_url, json_body):
        """Renders a human-readable error message from the given response data.

        :param status_code: the integer status code from an HTTP response
        :type status_code: int
        :param reason: human-readable text representation of the status code
        :type reason: str
        :param request_method: the HTTP method used for the request
        :type request_method: str
        :param request_url: the URL the request was sent to
        :type request_url: str
        :param json_body: the response body, parsed as JSON, or None if
                          parsing failed
        :type json_body: dict | list | str | int | bool | None
        :return: the rendered error message
        :rtype: str
        """

        if status_code == 400:
            template = 'Error on request [{} {}]: HTTP 400: {}{}'
            suffix = ''
            if json_body is not None:
                json_str = json.dumps(json_body, indent=2, sort_keys=True)
                suffix = ':\n' + json_str
            return template.format(request_method, request_url, reason, suffix)

        if status_code == 409:
            path = urllib.parse.urlparse(request_url).path
            path_name = (name for name in cls.RESOURCE_TYPES if name in path)
            resource_name = next(path_name, 'resource')

            template = ('Changes blocked: '
                        'deployment already in progress for {}.')
            return template.format(resource_name)

        if json_body is None:
            template = 'Error decoding response from [{}]: HTTP {}: {}'
            return template.format(request_url, status_code, reason)

        if not cls.ERROR_JSON_VALIDATOR.is_valid(json_body):
            log_str = 'Server did not return a message: %s'
            logger.error(log_str, json_body)

            return _default_dcos_error()

        message = json_body.get('message')
        if message is None:
            message = '\n'.join(err['error'] for err in json_body['errors'])
            return _default_dcos_error(message)

        return 'Error: {}'.format(message)

    def http_req(self, method_fn, path, *args, **kwargs):
        """Make an HTTP request, and raise a DCOS-specific exception for
        HTTP error codes.

        :param method_fn: function to call that invokes a specific HTTP method
        :type method_fn: function
        :param path: the endpoint path to append to this object's base URL
        :type path: str
        :param args: additional args to pass to `method_fn`
        :type args: [object]
        :param kwargs: kwargs to pass to `method_fn`
        :type kwargs: dict
        :returns: `method_fn` return value
        :rtype: requests.Response
        """

        url = self._base_url + path.lstrip('/')

        if 'timeout' not in kwargs:
            kwargs['timeout'] = self._timeout

        try:
            return method_fn(url, *args, **kwargs)
        except DCOSHTTPException as e:
            text = _get_response_text(e.response)
            logger.error('DCOS Error: %s\n%s',
                         e.response.reason, text)

            try:
                json_body = e.response.json()
            except Exception:
                logger.exception(
                    'Unable to decode response body as a JSON value: %r',
                    e.response)

                json_body = None

            message = RpcClient.response_error_message(
                status_code=e.response.status_code,
                reason=e.response.reason,
                request_method=e.response.request.method,
                request_url=e.response.request.url,
                json_body=json_body)
            raise DCOSException(message)


def _get_response_text(response):
    try:
        return response.text
    except Exception:
        return ''


def _default_dcos_error(message=""):
    """
    :param message: additional message
    :type message: str
    :returns: dcos specific error message
    :rtype: str
    """

    return ("Service likely misconfigured. Please check your proxy or "
            "Service URL settings. See dcos config --help. {}").format(
                message)
