import abc


class DCOSException(Exception):
    pass


class DCOSHTTPException(DCOSException):
    """ A wrapper around Response objects for HTTP error codes.

    :param response: requests Response object
    :type response: Response
    """
    def __init__(self, response):
        self.response = response

    def status(self):
        return self.response.status_code

    def __str__(self):
        return 'Error while fetching [{0}]: HTTP {1}: "{2}".'.format(
            self.response.request.url,
            self.response.status_code,
            self.response.reason)


class DCOSUnprocessableException(DCOSException):
    """ A wrapper around Response objects for HTTP 422
    error codes, Unprocessable JSON Entities.

    :param response: requests Response object
    :type response: Response
    """
    def __init__(self, response):
        self.response = response

    def status(self):
        return self.response.status_code

    def __str__(self):
        return 'Error while fetching [{0}]: HTTP {1}: "{2}".'.format(
            self.response.request.url,
            self.response.status_code,
            self.response.text)


class DCOSAuthenticationException(DCOSHTTPException):
    """A wrapper around Response objects for HTTP Authentication errors (401).

    :param response: requests Response object
    :type response: Response
    :param message: An optional message for the exception. This can be useful
                    to provide the user with more meaningful information.
    :type message: str
    """
    def __init__(self, response, message=None):
        if message is None:
            message = "Authentication failed. Please run `dcos auth login`."

        self.response = response
        self.message = message

    def __str__(self):
        return self.message


class DCOSAuthorizationException(DCOSHTTPException):
    """A wrapper around Response objects for HTTP Authorization errors (403).

    :param response: requests Response object
    :type response: Response
    """
    def __init__(self, response):
        self.response = response

    def __str__(self):
        return "You are not authorized to perform this operation."


class DCOSConnectionError(DCOSException):
    """An Error object for when a connection attempt fails.

    :param url: URL for the Request
    :type url: str
    """
    def __init__(self, url):
        self.url = url

    def __str__(self):
        return 'URL [{0}] is unreachable.'.format(self.url)


class DCOSBadRequest(DCOSHTTPException):
    """A wrapper around Response objects for HTTP Bad Request (400).

    :param response: requests Response object
    :type response: Response
    """
    def __init__(self, response):
        self.response = response

    def __str__(self):
        return "Bad request."


class Error(object):
    """Abstract class for describing errors."""

    @abc.abstractmethod
    def error(self):
        """Creates an error message

        :returns: The error message
        :rtype: str
        """

        raise NotImplementedError


class DefaultError(Error):
    """Construct a basic Error class based on a string

    :param message: String to use for the error message
    :type message: str
    """

    def __init__(self, message):
        self._message = message

    def error(self):
        return self._message
