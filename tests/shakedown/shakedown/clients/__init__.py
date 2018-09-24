from functools import lru_cache
from os import environ

from ..errors import DCOSException


@lru_cache(1)
def dcos_url():
    """Return the DC/OS URL as configured in the DC/OS library.
    :return: DC/OS cluster URL as a string
    """
    if 'DCOS_URL' in environ:
        return environ.get('DCOS_URL')
    else:
        raise DCOSException('DCOS_URL environment variable was not defined.')


def dcos_url_path(url_path):
    from six.moves import urllib
    return urllib.parse.urljoin(dcos_url(), url_path)


def dcos_service_url(service):
    """Return the URL of a service running on DC/OS, based on the value of
    shakedown.dcos.dcos_url() and the service name.
    :param service: the name of a registered DC/OS service, as a string
    :return: the full DC/OS service URL, as a string
    """
    return dcos_url_path("/service/{}/".format(service))
