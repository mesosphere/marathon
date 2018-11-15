"""Utilities for working with authenication and authorization
   Many of the functions here are for DC/OS Enterprise.
"""

import contextlib
import requests

from ..clients import dcos_url_path
from ..clients.authentication import authenticate, dcos_acs_token, DCOSAcsAuth
from ..clients.rpcclient import verify_ssl
from ..errors import DCOSHTTPException

from urllib.parse import urljoin


def _acl_url():
    return dcos_url_path('acs/api/v1/')


def add_user(uid, password, desc=None):
    """ Adds user to the DCOS Enterprise.  If not description
        is provided the uid will be used for the description.

        :param uid: user id
        :type uid: str
        :param password: password
        :type password: str
        :param desc: description of user
        :type desc: str
    """
    desc = uid if desc is None else desc
    user_object = {"description": desc, "password": password}
    acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
    auth = DCOSAcsAuth(dcos_acs_token())
    try:
        r = requests.put(acl_url, json=user_object, auth=auth, verify=verify_ssl())
        r.raise_for_status()
    except requests.HTTPError as e:
        if e.response.status_code == 409:
            pass
        else:
            raise


def get_user(uid):
    """ Returns a user from the DCOS Enterprise.  It returns None if none exists.

        :param uid: user id
        :type uid: str
        :return: User
        :rtype: dict
    """
    acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.get(acl_url, auth=auth, verify=verify_ssl())
    return r.json()


def remove_user(uid):
    """ Removes a user from the DCOS Enterprise.

        :param uid: user id
        :type uid: str
    """
    acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.delete(acl_url, auth=auth, verify=verify_ssl())
    r.raise_for_status()


def ensure_resource(rid):
    """ Creates or confirms that a resource is added into the DCOS Enterprise System.
        Example:  dcos:service:marathon:marathon:services:/example-secure

        :param rid: resource ID
        :type rid: str
    """
    acl_url = urljoin(_acl_url(), 'acls/{}'.format(rid))
    auth = DCOSAcsAuth(dcos_acs_token())
    try:
        r = requests.put(acl_url, json={'description': 'jope'}, auth=auth, verify=verify_ssl())
        r.raise_for_status()
    except requests.HTTPError as e:
        if e.response.status_code == 409:
            pass
        else:
            raise


def set_user_permission(rid, uid, action='full'):
    """ Sets users permission on a given resource.  The resource will be created
        if it doesn't exist.  Actions are: read, write, update, delete, full.

        :param uid: user id
        :type uid: str
        :param rid: resource ID
        :type rid: str
        :param action: read, write, update, delete or full
        :type action: str
    """
    rid = rid.replace('/', '%252F')
    # Create ACL if it does not yet exist.
    ensure_resource(rid)

    # Set the permission triplet.
    acl_url = urljoin(_acl_url(), 'acls/{}/users/{}/{}'.format(rid, uid, action))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.put(acl_url, auth=auth, verify=verify_ssl())
    assert r.status_code == 204


def remove_user_permission(rid, uid, action='full'):
    """ Removes user permission on a given resource.

        :param uid: user id
        :type uid: str
        :param rid: resource ID
        :type rid: str
        :param action: read, write, update, delete or full
        :type action: str
    """
    rid = rid.replace('/', '%252F')

    try:
        acl_url = urljoin(_acl_url(), 'acls/{}/users/{}/{}'.format(rid, uid, action))
        auth = DCOSAcsAuth(dcos_acs_token())
        r = requests.delete(acl_url, auth=auth, verify=verify_ssl())
        assert r.status_code == 204
    except DCOSHTTPException as e:
        if e.response.status_code != 400:
            raise


@contextlib.contextmanager
def new_dcos_user(user_id, password):
    """ Provides a context with a newly created user.
    """
    add_user(user_id, password, user_id)

    token = authenticate(user_id, password)
    yield token
    remove_user(user_id)


@contextlib.contextmanager
def dcos_user(user_id, password):
    """ Provides a context with user otherthan super
    """

    token = authenticate(user_id, password)
    yield token


def add_group(id, description=None):
    """ Adds group to the DCOS Enterprise.  If not description
        is provided the id will be used for the description.

        :param id: group id
        :type id: str
        :param desc: description of user
        :type desc: str
    """

    if not description:
        description = id
    data = {
        'description': description
    }
    acl_url = urljoin(_acl_url(), 'groups/{}'.format(id))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.put(acl_url, json=data, auth=auth, verify=verify_ssl())
    assert r.status_code == 201


def get_group(id):
    """ Returns a group from the DCOS Enterprise.  It returns None if none exists.

        :param id: group id
        :type id: str
        :return: Group
        :rtype: dict
    """
    acl_url = urljoin(_acl_url(), 'groups/{}'.format(id))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.get(acl_url, auth=auth, verify=verify_ssl())
    return r.json()


def remove_group(id):
    """ Removes a group from the DCOS Enterprise.  The group is
        removed regardless of associated users.

        :param id: group id
        :type id: str
    """
    acl_url = urljoin(_acl_url(), 'groups/{}'.format(id))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.delete(acl_url, auth=auth, verify=verify_ssl())
    r.raise_for_status()


def add_user_to_group(uid, gid, exist_ok=True):
    """ Adds a user to a group within DCOS Enterprise.  The group and
        user must exist.

        :param uid: user id
        :type uid: str
        :param gid: group id
        :type gid: str
        :param exist_ok: True if it is ok for the relationship to pre-exist.
        :type exist_ok: bool
    """
    acl_url = urljoin(_acl_url(), 'groups/{}/users/{}'.format(gid, uid))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.put(acl_url, auth=auth, verify=verify_ssl())
    assert r.status_code == 204


def remove_user_from_group(uid, gid):
    """ Removes a user from a group within DCOS Enterprise.

        :param uid: user id
        :type uid: str
        :param gid: group id
        :type gid: str
    """
    acl_url = urljoin(_acl_url(), 'groups/{}/users/{}'.format(gid, uid))
    auth = DCOSAcsAuth(dcos_acs_token())
    r = requests.delete(acl_url, auth=auth, verify=verify_ssl())
    assert r.status_code == 204
