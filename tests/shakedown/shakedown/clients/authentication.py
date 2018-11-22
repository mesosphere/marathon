import logging
import requests
import retrying
import toml

from functools import lru_cache
from os import environ, path
from . import dcos_url_path
from .cli import run_dcos_command
from ..errors import DCOSAuthenticationException


logger = logging.getLogger(__name__)


@lru_cache()
def read_shakedown_config():
    """ Read configuration options from ~/.shakedown (if exists)
        :return: a dict of arguments
        :rtype: dict
    """
    configfile = path.expanduser('~/.shakedown')
    args = dict()
    if path.isfile(configfile):
        with open(configfile, 'r') as f:
            config = toml.loads(f.read())
        for key, value in config.items():
            param = key.replace('-', '_')
            args[param] = value
    return args


def dcos_username():
    return environ.get('DCOS_USERNAME') or read_shakedown_config().get('username')


def dcos_password():
    return environ.get('DCOS_PASSWORD') or read_shakedown_config().get('password')


def authenticate(username, password):
    """Authenticate with a DC/OS cluster and return an ACS token.
    return: ACS token
    """
    url = dcos_url_path('acs/api/v1/auth/login')

    creds = {
        'uid': username,
        'password': password
    }

    response = requests.post(url, json=creds, headers={'Content-Type': 'application/json'}, auth=None, verify=False)

    response.raise_for_status()
    return response.json()['token']


def authenticate_oauth(oauth_token):
    """Authenticate by checking for a valid OAuth token.
    return: ACS token
    """
    url = dcos_url_path('acs/api/v1/auth/login')
    payload = {'token': oauth_token}
    response = requests.post(url, json=payload, headers={'Content-Type': 'application/json'}, auth=None, verify=False)

    response.raise_for_status()
    return response.json()['token']


@lru_cache(1)
@retrying.retry(wait_fixed=5000, stop_max_attempt_number=60)
def dcos_acs_token():
    """Return the DC/OS ACS token as configured in the DC/OS library.
    :return: DC/OS ACS token as a string
    """
    logger.info('Authenticating with DC/OS cluster...')

    # Try token from dcos cli session
    try:
        token, _, _ = run_dcos_command('config show core.dcos_acs_token', raise_on_error=True, print_output=False)
        token = token.rstrip()

        url = dcos_url_path('/system/health/v1')
        requests.get(url, auth=DCOSAcsAuth(token), verify=False).raise_for_status()
        logger.info('Authentication using DC/OS CLI session ✓')
        return token
    except Exception:
        logger.exception('Authentication using DC/OS CLI session ✕')

    # Try OAuth authentication
    oauth_token = environ.get('SHAKEDOWN_OAUTH_TOKEN') or read_shakedown_config().get('oauth_token')
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
    username = dcos_username()
    password = dcos_password()
    if username is not None and password is not None:
        try:
            token = authenticate(username, password)
            logger.info('Authentication using username and password ✓')
            return token
        except Exception:
            logger.exception('Authentication using username and password ✕')
    else:
        logger.warning('No username and password are defined in environment variables or .shakedown.')

    msg = 'Could not authenticate with DC/OS CLI session, OAuth token nor username and password.'
    logger.error(msg)
    raise DCOSAuthenticationException(response=None, message=msg)


class DCOSAcsAuth(requests.auth.AuthBase):
    """Invokes DCOS Authentication flow for given Request object."""
    def __init__(self, token):
        self.token = token

    def __call__(self, r):
        r.headers['Authorization'] = "token={}".format(self.token)
        return r
