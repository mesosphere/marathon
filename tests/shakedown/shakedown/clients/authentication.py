import logging
import toml

from functools import lru_cache
from os import environ, path
from shakedown import http
from shakedown.dcos import gen_url
from shakedown.errors import DCOSException


logger = logging.getLogger(__name__)


@lru_cache()
def read_config():
    """ Read configuration options from ~/.shakedown (if exists)
        :return: a dict of arguments
        :rtype: dict
    """
    configfile = path.expanduser('~/.shakedown')
    args = dict()
    if path.isfile(configfile):
        with open(configfile, 'r') as f:
            config = toml.loads(f.read())
        for key in config:
            param = key.replace('-', '_')
            args[param] = config[key]
    return args


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


@lru_cache()
def dcos_acs_token():
    """Return the DC/OS ACS token as configured in the DC/OS library.
    :return: DC/OS ACS token as a string
    """
    logger.info('Authenticating with DC/OS cluster...')

    # Try OAuth authentication
    oauth_token = environ.get('SHAKEDOWN_OAUTH_TOKEN') or read_config().get('oauth_token')
    if oauth_token is not None:
        try:
            token = authenticate_oauth(oauth_token)
            logger.info('Authentication using OAuth token ✓')
            return token
        except Exception:
            logger.exception('Authentication using OAuth token ✕')
    else:
        logger.warning('No OAuth token is defined in SHAKEDOWN_OAUTH_TOKEN or .shakedown.')

    # Try username and password authentication
    username = environ.get('SHAKEDOWN_USERNAME') or read_config().get('username')
    password = environ.get('SHAKEDOWN_PASSWORD') or read_config().get('password')
    if username is not None and password is not None:
        try:
            token = authenticate(username, password)
            logger.info('Authentication using username and password ✓')
            return token
        except Exception:
            logger.exception('Authentication using username and password ✕')
    else:
        logger.warning('No username and password are defined in enviroment variables or .shakedown.')

    raise DCOSException('Could not authenticate with with OAuth token nor username and password.')
