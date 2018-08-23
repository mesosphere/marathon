import getpass
import sys
import textwrap
import time
import webbrowser

import jwt
from six.moves import urllib
from six.moves.urllib.parse import urlparse

from dcos import config, http, util
from dcos.errors import (DCOSAuthenticationException, DCOSException,
                         DCOSHTTPException)

logger = util.get_logger(__name__)


def _get_auth_scheme(response):
    """Return authentication scheme requested by server for
       'acsjwt' (DC/OS acs auth), 'oauthjwt' (DC/OS acs oauth), or
       None (no auth)

    :param response: requests.response
    :type response: requests.Response
    :returns: auth_scheme
    :rtype: str | None
    """

    if 'WWW-Authenticate' in response.headers:
        auths = response.headers['WWW-Authenticate'].split(',')
        scheme = next((auth_type.rstrip().lower() for auth_type in auths
                       if auth_type.rstrip().lower().startswith("acsjwt") or
                       auth_type.rstrip().lower().startswith("oauthjwt")),
                      None)
        if scheme:
            scheme_info = scheme.split("=")
            auth_scheme = scheme_info[0].split(" ")[0].lower()
            return auth_scheme
        else:
            msg = ("Server responded with an HTTP 'www-authenticate' field of "
                   "'{}', DC/OS only supports ['oauthjwt', 'acsjwt']".format(
                       response.headers['WWW-Authenticate']))
            raise DCOSException(msg)
    else:
        logger.debug("HTTP response: no www-authenticate field found")
        return


def _prompt_user_for_token(url, token_type):
    """Get Token from user

    :param url: url for user to go to
    :type url: str
    :param token_type: type of token to be received
    :type token_type: str
    :returns: token show to user by browser
    :rtype: str
    """

    msg = textwrap.dedent("""\
        If your browser didn't open, please go to the following link:

            {url}

        Enter {token_type}: """)
    msg = msg.lstrip().format(url=url, token_type=token_type)

    try:
        with util.silent_output():
            webbrowser.open_new_tab(url)
    except webbrowser.Error as exc:
        logger.warning(
            'Exception occurred while calling webbrowser.open(%r): %s',
            url, exc,
        )
        pass
    sys.stderr.write(msg)
    sys.stderr.flush()
    token = sys.stdin.readline().strip()
    return token


def _get_dcostoken_by_post_with_creds(dcos_url, creds):
    """
    Get DC/OS Authentication token by POST to `acs/api/v1/auth/login`
    with specific credentials. Credentials can be uid/password for
    username/password authentication, OIDC ID token for implicit OIDC flow
    (used for open DC/OS), or uid/token for service accounts (where token is a
    jwt token encoded with private key).

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param creds: credentials to login endpoint
    :type creds: {}
    :returns: DC/OS Authentication Token
    :rtype: str
    """

    url = dcos_url.rstrip('/') + '/acs/api/v1/auth/login'
    response = http._request('post', url, json=creds)

    token = None
    if response.status_code == 200:
        token = response.json()['token']
        config.set_val("core.dcos_acs_token", token)

    return token


def _prompt_for_uid_password(username, hostname):
    """Get username/password for auth

    :param username: username user for authentication
    :type username: str
    :param hostname: hostname for credentials
    :type hostname: str
    :returns: username, password
    :rtype: str, str
    """

    if username is None:
        sys.stdout.write("{}'s username: ".format(hostname))
        sys.stdout.flush()
        username = sys.stdin.readline().strip()

    password = getpass.getpass("{}@{}'s password: ".format(username, hostname))

    return username, password


def _get_dcostoken_by_dcos_uid_password_auth(
        dcos_url, username=None, password=None):
    """
    Get DC/OS Authentication Token by DC/OS uid password auth

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param username: username to auth with
    :type username: str
    :param password: password to auth with
    :type password: str
    :returns: DC/OS Authentication Token if successful login
    :rtype: str
    """

    url = urlparse(dcos_url)
    hostname = url.hostname
    username = username or url.username
    password = password or url.password

    if password is None:
        username, password = _prompt_for_uid_password(username, hostname)

    creds = {"uid": username, "password": password}
    return _get_dcostoken_by_post_with_creds(dcos_url, creds)


def dcos_uid_password_auth(dcos_url, username=None, password=None):
    """
    Authenticate user using DC/OS uid password auth

    Raises exception if authentication fails.

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param username: username to auth with
    :type username: str
    :param password: password to auth with
    :type password: str
    :rtype: None
    """

    dcos_token = _get_dcostoken_by_dcos_uid_password_auth(
            dcos_url, username, password)
    if not dcos_token:
        raise DCOSException("Authentication failed")
    else:
        return


def dcos_cred_auth(dcos_url, username=None, password=None):
    """
    Authenticate user using DC/OS credentials

    Raises exception if authentication fails.

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param username: username to auth with
    :type username: str
    :param password: password to auth with
    :type password: str
    :rtype: None
    """

    dcos_token = _get_dcostoken_by_dcos_uid_password_auth(
            dcos_url, username, password)
    if not dcos_token:
        raise DCOSException("Authentication failed")
    else:
        return


def oidc_implicit_flow_auth(dcos_url):
    """
    Authenticate user using OIDC implict flow

    Raises exception if authentication fails.

    :param dcos_url: url to cluster
    :type dcos_url: str
    :rtype: None
    """

    dcos_auth_token = _get_dcostoken_by_oidc_implicit_flow(dcos_url)
    if not dcos_auth_token:
        raise DCOSException("Authentication failed")
    else:
        return


def _get_dcostoken_by_oidc_implicit_flow(dcos_url):
    """
    Get DC/OS Authentication Token by OIDC implicit flow

    :param dcos_url: url to cluster
    :type dcos_url: str
    :returns: DC/OS Authentication Token
    :rtype: str
    """

    oauth_login = '/login?redirect_uri=urn:ietf:wg:oauth:2.0:oob'
    url = dcos_url.rstrip('/') + oauth_login
    oidc_token = _prompt_user_for_token(url, "OpenID Connect ID Token")
    creds = {"token": oidc_token}
    return _get_dcostoken_by_post_with_creds(dcos_url, creds)


def servicecred_auth(dcos_url, username, key_path):
    """
    Get DC/OS Authentication token by browser prompt

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param username: username user for authentication
    :type username: str
    :param key_path: path to service key
    :param key_path: str
    :rtype: None
    """

    # 'token' below contains a short lived service login token. This requires
    # the local machine to be in sync with DC/OS nodes enough that the 5min
    # padding here is enough time to validate the token.
    creds = {
        'uid': username,
        'token': jwt.encode(
            {
                'exp': int(time.time()+5*60),
                'uid': username
            },
            util.read_file_secure(key_path),
            algorithm='RS256')
        .decode('ascii')
    }

    dcos_token = _get_dcostoken_by_post_with_creds(dcos_url, creds)
    if not dcos_token:
        raise DCOSException("Authentication failed")
    else:
        return


def browser_prompt_auth(dcos_url, provider_info):
    """
    Get DC/OS Authentication token by browser prompt

    :param dcos_url: url to cluster
    :type dcos_url: str
    :param provider_info: info about provider to auth with
    :param provider_info: str
    :rtype: None
    """

    start_flow_url = provider_info["config"]["start_flow_url"].lstrip('/')
    if not urlparse(start_flow_url).netloc:
        start_flow_url = dcos_url.rstrip('/') + start_flow_url

    dcos_token = _prompt_user_for_token(
        start_flow_url, "DC/OS Authentication Token")

    # verify token
    endpoint = '/pkgpanda/active.buildinfo.full.json'
    url = urllib.parse.urljoin(dcos_url, endpoint)
    response = http._request('HEAD', url, auth=http.DCOSAcsAuth(dcos_token))
    if response.status_code in [200, 403]:
        config.set_val("core.dcos_acs_token", dcos_token)
    else:
        raise DCOSException("Authentication failed")


def header_challenge_auth(dcos_url):
    """
    Triggers authentication using scheme specified in www-authenticate header.

    Raises exception if authentication fails.

    :param dcos_url: url to cluster
    :type dcos_url: str
    :rtype: None
    """

    # hit protected endpoint which will prompt for auth if cluster has auth
    endpoint = '/pkgpanda/active.buildinfo.full.json'
    url = urllib.parse.urljoin(dcos_url, endpoint)
    response = http._request('HEAD', url)
    auth_scheme = _get_auth_scheme(response)

    for _ in range(3):
        if response.status_code == 401:
            # this header claims the cluster is open DC/OS 1.7, 1.8 or 1.9
            # and supports OIDC implicit auth
            if auth_scheme == "oauthjwt":
                token = _get_dcostoken_by_oidc_implicit_flow(dcos_url)
            # auth_scheme == "acsjwt"
            # this header claims the cluster is enterprise DC/OS 1.7, 1.8 or
            # 1.9 and supports username/pawword auth
            else:
                token = _get_dcostoken_by_dcos_uid_password_auth(dcos_url)

            if token is not None:
                break
        elif response.status_code == 200:
            break
    else:
        raise DCOSAuthenticationException(response)


def get_providers():
    """
    Returns dict of providers configured on cluster

    :returns: configured providers
    :rtype: {}
    """

    dcos_url = config.get_config_val("core.dcos_url").rstrip('/')
    endpoint = '/acs/api/v1/auth/providers'
    url = urllib.parse.urljoin(dcos_url, endpoint)
    try:
        providers = http.get(url)
        return providers.json()
    # this endpoint should only have authentication in DC/OS 1.8
    except DCOSAuthenticationException:
        msg = "This command is not supported for your cluster"
        raise DCOSException(msg)
    except DCOSHTTPException as e:
        if e.response.status_code == 404:
            msg = "This command is not supported for your cluster"
            raise DCOSException(msg)

    return {}


def auth_type_description(provider_info):
    """
    Returns human readable description of auth type

    :param provider_info: info about auth provider
    :type provider_info: dict
    :returns: human readable description of auth type
    :rtype: str
    """

    auth_type = provider_info.get("authentication-type")
    if auth_type == "dcos-uid-password":
        msg = ("Authenticate using a standard DC/OS user account "
               "(using username and password)")
    elif auth_type == "dcos-uid-servicekey":
        msg = ("Authenticate using a DC/OS service user account "
               "(using username and private key)")
    elif auth_type == "dcos-uid-password-ldap":
        msg = ("Authenticate using an LDAP user account "
               "(using username and password)")
    elif auth_type == "saml-sp-initiated":
        msg = "Authenticate using SAML 2.0 ({})".format(
                provider_info["description"])
    elif auth_type in ["oidc-authorization-code-flow", "oidc-implicit-flow"]:
        msg = "Authenticate using OpenID Connect ({})".format(
                provider_info["description"])
    else:
        raise DCOSException("Unknown authentication type")

    return msg
