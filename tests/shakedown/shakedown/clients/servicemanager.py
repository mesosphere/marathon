from . import cosmos, dcos_service_url
from .packagemanager import cosmos_error
from ..errors import (DCOSAuthenticationException,
                      DCOSAuthorizationException,
                      DCOSException,
                      DCOSHTTPException)


class ServiceManager(object):
    """A manager for DC/OS services"""

    def __init__(self, base_url=None):
        self.base_url = base_url if base_url else dcos_service_url('cosmos')
        self.cosmos = cosmos.Cosmos(self.base_url)

    def enabled(self):
        """
        Returns whether service manager is enabled.

        :return: true whether this service is enabled, false otherwise
        :rtype: bool
        """
        return self.cosmos.enabled()

    @cosmos_error
    def start_service(self, package_name, package_version, options):
        """
        Starts a service that has been added to the cluster via
        cosmos' package/add endpoint.

        :param package_name: the name of the package to start
        :type package_name: str
        :param package_version: the version of the package to start
        :type package_version: None | str
        :param options: the options for the service
        :type options: None | dict
        :return: the response of cosmos' service/start endpoint
        :rtype: requests.Response
        """
        endpoint = 'service/start'
        json = {'packageName': package_name}
        if package_version is not None:
            json['packageVersion'] = package_version
        if options is not None:
            json['options'] = options
        try:
            return self.cosmos.call_endpoint(endpoint, json=json)
        except (DCOSAuthenticationException, DCOSAuthorizationException):
            raise
        except DCOSHTTPException as e:
            if e.status() == 404:
                message = 'Your version of DC/OS ' \
                          'does not support this operation'
                raise DCOSException(message)
            else:
                return e.response
