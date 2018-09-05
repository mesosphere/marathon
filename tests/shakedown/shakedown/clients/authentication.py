import logging

from functools import lru_cache
from os import environ
from shakedown import http
from shakedown.dcos import gen_url
from shakedown.errors import DCOSException


logger = logging.getLogger(__name__)


def authenticate(username, password):
    """Authenticate with a DC/OS cluster and return an ACS token.
    return: ACS token
    """
    url = gen_url('acs/api/v1/auth/login')

    creds = {
        'uid': username,
        'password': password
    }

    response = http.request('post', url, json=creds)

    response.raise_for_status()
    return response.json()['token']


def authenticate_oauth(oauth_token):
    """Authenticate by checking for a valid OAuth token.
    return: ACS token
    """
    url = gen_url('acs/api/v1/auth/login')

    creds = {
        'token': oauth_token
    }

    response = http.request('post', url, json=creds)

    response.raise_for_status()
    return response.json()['token']


@lru_cache
def dcos_acs_token():
    """Return the DC/OS ACS token as configured in the DC/OS library.
    :return: DC/OS ACS token as a string
    """
    logger.info('Authenticating with DC/OS cluster...')

    if 'SHAKEDOWN_OAUTH_TOKEN' in environ:
        try:
            token = authenticate_oauth(environ.get('SHAKEDOWN_OAUTH_TOKEN'))
            logger.info('Authentication using OAuth token ✓')
            return token
        except Exception:
            logger.exception('Authentication using OAuth token ✕')
    else:
        logger.warning('No SHAKEDOW_OAUTH_TOKEN environment variable is defined.')

    if 'SHAKEDOWN_USERNAME' in environ and 'SHAKEDOWN_PASSWORD' in environ:
        try:
            username = environ.get('SHAKEDOWN_USERNAME')
            password = environ.get('SHAKEDOWN_PASSWORD')
            token = authenticate(username, password)
            logger.info('Authentication using username and password ✓')
            return token
        except Exception:
            logger.exception('Authentication using username and password ✕')
    else:
        logger.warning('SHAKEDOWN_USERNAME and SHAKEDOWN_PASSWORD enviroment variables are not defined.')

    raise DCOSException('Could not authenticate with with OAuth token or username and password.')
