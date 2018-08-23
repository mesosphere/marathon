from six.moves import urllib

from dcos import config, http, util
from dcos.errors import (DCOSAuthenticationException,
                         DCOSAuthorizationException,
                         DCOSBadRequest,
                         DCOSException,
                         DCOSHTTPException)

logger = util.get_logger(__name__)


class Cosmos(object):
    """
    A wrapper on cosmos that abstracts away http requests

    :param cosmos_url: the url of cosmos
    :type cosmos_url: str
    """

    def __init__(self, cosmos_url=None):
        if cosmos_url is None:
            self.cosmos_url = get_cosmos_url()
        else:
            self.cosmos_url = cosmos_url

        def _data(versions, http_method):
            """
            Create an object with the data for an endpoint.

            :param versions: the accept versions in order
            of priority.
            :type versions: list[str]
            :param http_method: should be 'post' or 'get'
            :type http_method: str
            :return:
            """
            return {'versions': versions, 'http_method': http_method}

        # This structure holds information about an endpoint. Currently
        # the information stored is the return type versions, and the
        # http request method. These two field should be stored in a
        # dictionary of the form:
        # {'versions': versions, 'http_method': http_method}.
        self._endpoint_data = {
            'capabilities': _data(['v1'], 'get'),
            'package/add': _data(['v1'], 'post'),
            'package/describe': _data(['v3', 'v2'], 'post'),
            'package/install': _data(['v2'], 'post'),
            'package/list': _data(['v1'], 'post'),
            'package/list-versions': _data(['v1'], 'post'),
            'package/render': _data(['v1'], 'post'),
            'package/repository/add': _data(['v1'], 'post'),
            'package/repository/delete': _data(['v1'], 'post'),
            'package/repository/list': _data(['v1'], 'post'),
            'package/search': _data(['v1'], 'post'),
            'package/uninstall': _data(['v1'], 'post'),
            'service/start': _data(['v1'], 'post')
        }

        self._special_content_types = {
            ('capabilities', 'v1'):
                _format_media_type('capabilities', 'v1', '')
        }

        self._special_accepts = {
            ('capabilities', 'v1'):
                _format_media_type('capabilities', 'v1', '')
        }

    def enabled(self):
        """
        Returns whether or not cosmos is enabled on specified dcos cluster

        :return: true if cosmos is enabled, false otherwise
        :rtype: bool
        """
        try:
            response = self.call_endpoint(
                'capabilities')
        # return `Authentication failed` error messages
        except DCOSAuthenticationException:
            raise
        # Authorization errors mean endpoint exists, and user could be
        # authorized for the command specified, not this endpoint
        except DCOSAuthorizationException:
            return True
        # allow exception through so we can show user actual http exception
        # except 404, because then the url is fine, just not cosmos enabled
        except DCOSHTTPException as e:
            logger.exception(e)
            return e.status() != 404
        except Exception as e:
            logger.exception(e)
            return True
        return response.status_code == 200

    def call_endpoint(self,
                      endpoint,
                      headers=None,
                      data=None,
                      json=None,
                      **kwargs):
        """
        Gets the Response object returned by comos at endpoint

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :param headers: these header values will appear
        in the request headers.
        :type headers: None | dict[str, str]
        :param data: the request's body
        :type data: dict | bytes | file-like object
        :param json: JSON request body
        :type json: dict
        :param kwargs: Additional arguments to requests.request
                       (see py:func:`request`)
        :type kwargs: dict
        :return: the Response object returned by cosmos
        :rtype: requests.Response
        """
        url = self._get_endpoint_url(endpoint)
        request_versions = self._get_request_version_preferences(endpoint)
        headers_preference = list(map(
            lambda version: self._get_header(
                endpoint, version, headers),
            request_versions))
        http_request_type = self._get_http_method(endpoint)
        return self._cosmos_request(
            url,
            http_request_type,
            headers_preference,
            data,
            json,
            **kwargs)

    def _cosmos_request(self,
                        url,
                        http_request_type,
                        headers_preference,
                        data=None,
                        json=None,
                        **kwargs):
        """
        Gets a Response object obtained by calling cosmos
        at the url 'url'. Will attempt each of the headers
        in headers_preference in order until success.

        :param url: the url of a cosmos endpoint
        :type url: str
        :param headers_preference: a list of request headers
        in order of preference. Each header
        will be attempted until they all fail or the request succeeds.
        :type headers_preference: list[dict[str, str]]
        :param data: the request's body
        :type data: dict | bytes | file-like object
        :param json: JSON request body
        :type json: dict
        :param kwargs: Additional arguments to requests.request
                       (see py:func:`request`)
        :type kwargs: dict
        :return: response returned by calling cosmos at url
        :rtype: requests.Response
        """
        try:
            headers = headers_preference[0]
            if http_request_type is 'post':
                response = http.post(
                    url, data=data, json=json, headers=headers, **kwargs)
            else:
                response = http.get(
                    url, data=data, json=json, headers=headers, **kwargs)
            if not _matches_expected_response_header(headers,
                                                     response.headers):
                raise DCOSException(
                    'Server returned incorrect response type, '
                    'expected {} but got {}'.format(
                        headers.get('Accept'),
                        response.headers.get('Content-Type')))
            return response
        except DCOSBadRequest as e:
            if len(headers_preference) > 1:
                # reattempt with one less item in headers_preference
                return self._cosmos_request(url,
                                            http_request_type,
                                            headers_preference[1:],
                                            data,
                                            json,
                                            **kwargs)
            else:
                raise e

    def _get_endpoint_url(self, endpoint):
        """
        Gets the url for the cosmos endpoint 'endpoint'

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :return: the url of endpoint
        :rtype: str
        """
        return urllib.parse.urljoin(self.cosmos_url, endpoint)

    def _get_request_version_preferences(self, endpoint):
        """
        Gets the list of versions for endpoint in preference order.
        The first item is most preferred, and last is least preferred.

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :return: list of versions in preference order
        :rtype: list[str]
        """
        return self._endpoint_data.get(endpoint).get('versions')

    def _get_http_method(self, endpoint):
        """
        Gets the http method cosmos expects for the endpoint
        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :return: http method type
        :rtype: str
        """
        return self._endpoint_data.get(endpoint).get('http_method')

    def _get_header(self, endpoint, version, headers=None):
        """
        Given an cosmos endpoint, a version, and any extra header values,
        gets the header that can be used to query cosmos at endpoint.
        Any key in headers will appear in the final header. In effect the
        user function can overwrite the default header.

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :param version: The version of the request
        :type version: str
        :param headers: extra keys for the header
        :type headers: dict[str, str]
        :return: a header that can be used to query cosmos at endpoint
        :rtype: dict[str, str]
        """
        simple_header = {
            'Content-Type': self._get_content_type(endpoint),
            'Accept': self._get_accept(endpoint, version)
        }
        return _merge_dict(simple_header, headers)

    def _endpoint_exists(self, endpoint):
        """

        :param endpoint: a possible cosmos endpoint
        :type endpoint: str
        :return: true if endpoint is a valid cosmos endpoint,
        false otherwise
        :rtype: bool
        """
        return endpoint in self._endpoint_data

    def _get_accept(self, endpoint, version):
        """
        Gets the value for the Accept header key for
        the cosmos request at endpoint.

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :param version: The version of the request
        :type version: str
        :return: the value for the Accept header key for endpoint
        :rtype: str
        """
        if (endpoint, version) in self._special_accepts:
            return self._special_accepts[(endpoint, version)]
        return _format_media_type(endpoint, version, 'response')

    def _get_content_type(self, endpoint):
        """
        Gets the value for the Content-Type header key for
        the cosmos request at endpoint.

        :param endpoint: a cosmos endpoint, of the form 'x/y',
        for example 'package/repo/add' or 'service/start'
        :type endpoint: str
        :return: the value for the Content-Type header key for endpoint
        :rtype: str
        """
        version = 'v1'
        if (endpoint, version) in self._special_content_types:
            return self._special_content_types[(endpoint, version)]
        return _format_media_type(endpoint, version, 'request')


def _format_media_type(endpoint, version, suffix):
    """
    Formats a value for a cosmos Content-Type or Accept header key.

    :param endpoint: a cosmos endpoint, of the form 'x/y',
    for example 'package/repo/add', 'service/start', or 'package/error'
    :type endpoint: str
    :param version: The version of the request
    :type version: str
    :param suffix: The string that will be appended to
    endpoint type, most commonly 'request' or 'response'
    :type suffix: str
    :return: a formatted value for a Content-Type or Accept header key
    :rtype: str
    """
    prefix = endpoint.replace('/', '.')
    separator = '-' if suffix else ''
    return ('application/vnd.dcos.{}{}{}'
            '+json;charset=utf-8;version={}').format(prefix,
                                                     separator,
                                                     suffix,
                                                     version)


def _matches_expected_response_header(request_headers, response_headers):
    """
    Returns true if the Content-Type value of the response header matches the
    Accept value of the request header, false otherwise

    :param request_headers: the headers for a cosmos request
    :type request_headers: dict[str, str]
    :param response_headers: the headers for a cosmos response
    :type response_headers: dict[str, str]
    :return: true if the Content-Type value of the response header matches the
    Accept value of the request header, false otherwise
    :rtype: bool
    """
    return (request_headers.get('Accept')
            in response_headers.get('Content-Type'))


def get_cosmos_url():
    """
    Gets the cosmos url

    :returns: cosmos base url
    :rtype: str
    """
    toml_config = config.get_config()
    cosmos_url = config.get_config_val('package.cosmos_url', toml_config)
    if cosmos_url is None:
        cosmos_url = config.get_config_val('core.dcos_url', toml_config)
        if cosmos_url is None:
            raise config.missing_config_exception(['core.dcos_url'])
    return cosmos_url


def _merge_dict(a, b):
    """
    Given two dicts, merge them into a new dict as a
    shallow copy. Keys on dictionary b will overwrite keys
    on dictionary a.

    :param a: a dictionary, may be None
    :type a: None | dict
    :param b: a dictionary, may be None
    :type b: None | dict
    :return: the result of merging a with b
    :rtype: dict
    """
    if a is None and b is None:
        return {}

    if a is None:
        return b.copy()

    if b is None:
        return a.copy()

    z = a.copy()
    z.update(b)
    return z
