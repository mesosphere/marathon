from sseclient import SSEClient

from . import http


def get(url, **kwargs):
    """ Make a get request to streaming endpoint which
    implements SSE (Server sent events). The parameter session=http
    will ensure we are using `dcos.http` module with all required auth
    headers.

    :param url: server sent events streaming URL
    :type url: str
    :param kwargs: arbitrary params for requests
    :type kwargs: dict
    :return: instance of sseclient.SSEClient
    :rtype: sseclient.SSEClient
    """
    return SSEClient(url, session=http, **kwargs)
