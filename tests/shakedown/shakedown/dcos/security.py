"""Utilities for working with authenication and authorization
   Many of the functions here are for DC/OS Enterprise.
"""

from shakedown import *
from urllib.parse import urljoin
from dcos import http
import pytest

from dcos.errors import DCOSHTTPException


def _acl_url():
    return urljoin(dcos_url(), 'acs/api/v1/')


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
    try:
        desc = uid if desc is None else desc
        user_object = {"description": desc, "password": password}
        acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
        r = http.put(acl_url, json=user_object)
        assert r.status_code == 201
    except DCOSHTTPException as e:
        # already exists
        if e.response.status_code != 409:
            raise


def get_user(uid):
    """ Returns a user from the DCOS Enterprise.  It returns None if none exists.

        :param uid: user id
        :type uid: str
        :return: User
        :rtype: dict
    """
    try:
        acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
        r = http.get(acl_url)
        return r.json()
        # assert r.status_code == 201
    except DCOSHTTPException as e:
        if e.response.status_code == 400:
            return None
        else:
            raise


def remove_user(uid):
    """ Removes a user from the DCOS Enterprise.

        :param uid: user id
        :type uid: str
    """
    try:
        acl_url = urljoin(_acl_url(), 'users/{}'.format(uid))
        r = http.delete(acl_url)
        assert r.status_code == 204
    except DCOSHTTPException as e:
        # doesn't exist
        if e.response.status_code != 400:
            raise


def ensure_resource(rid):
    """ Creates or confirms that a resource is added into the DCOS Enterprise System.
        Example:  dcos:service:marathon:marathon:services:/example-secure

        :param rid: resource ID
        :type rid: str
    """
    try:
        acl_url = urljoin(_acl_url(), 'acls/{}'.format(rid))
        r = http.put(acl_url, json={'description': 'jope'})
        assert r.status_code == 201
    except DCOSHTTPException as e:
        if e.response.status_code != 409:
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
    try:
        acl_url = urljoin(_acl_url(), 'acls/{}/users/{}/{}'.format(rid, uid, action))
        r = http.put(acl_url)
        assert r.status_code == 204
    except DCOSHTTPException as e:
        if e.response.status_code != 409:
            raise


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
        r = http.delete(acl_url)
        assert r.status_code == 204
    except DCOSHTTPException as e:
        if e.response.status_code != 400:
            raise


@contextlib.contextmanager
def no_user():
    """ Provides a context with no logged in user.
    """
    o_token = dcos_acs_token()
    dcos.config.set_val('core.dcos_acs_token', '')
    yield
    dcos.config.set_val('core.dcos_acs_token', o_token)


@contextlib.contextmanager
def new_dcos_user(user_id, password):
    """ Provides a context with a newly created user.
    """
    o_token = dcos_acs_token()
    shakedown.add_user(user_id, password, user_id)

    token = shakedown.authenticate(user_id, password)
    dcos.config.set_val('core.dcos_acs_token', token)
    yield
    dcos.config.set_val('core.dcos_acs_token', o_token)
    shakedown.remove_user(user_id)


@contextlib.contextmanager
def dcos_user(user_id, password):
    """ Provides a context with user otherthan super
    """

    o_token = dcos_acs_token()

    token = shakedown.authenticate(user_id, password)
    dcos.config.set_val('core.dcos_acs_token', token)
    yield
    dcos.config.set_val('core.dcos_acs_token', o_token)


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
    try:
        r = http.put(acl_url, json=data)
        assert r.status_code == 201
    except DCOSHTTPException as e:
        if e.response.status_code != 409:
            raise


def get_group(id):
    """ Returns a group from the DCOS Enterprise.  It returns None if none exists.

        :param id: group id
        :type id: str
        :return: Group
        :rtype: dict
    """
    acl_url = urljoin(_acl_url(), 'groups/{}'.format(id))
    try:
        r = http.get(acl_url)
        return r.json()
    except DCOSHTTPException as e:
        if e.response.status_code != 400:
            raise


def remove_group(id):
    """ Removes a group from the DCOS Enterprise.  The group is
        removed regardless of associated users.

        :param id: group id
        :type id: str
    """
    acl_url = urljoin(_acl_url(), 'groups/{}'.format(id))
    try:
        r = http.delete(acl_url)
        print(r.status_code)
    except DCOSHTTPException as e:
        if e.response.status_code != 400:
            raise


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
    try:
        r = http.put(acl_url)
        assert r.status_code == 204
    except DCOSHTTPException as e:
        if e.response.status_code == 409 and exist_ok:
            pass
        else:
            raise


def remove_user_from_group(uid, gid):
    """ Removes a user from a group within DCOS Enterprise.

        :param uid: user id
        :type uid: str
        :param gid: group id
        :type gid: str
    """
    acl_url = urljoin(_acl_url(), 'groups/{}/users/{}'.format(gid, uid))
    try:
        r = http.delete(acl_url)
        assert r.status_code == 204
    except dcos.errors.DCOSBadRequest:
        pass


@pytest.fixture(scope="function")
def credentials():
    """ Fixture to ensure that SU credentials are restored for auth tests.
        @pytest.mark.usefixtures('credentials') for a test
    """
    o_token = dcos_acs_token()
    yield
    dcos.config.set_val('core.dcos_acs_token', o_token)
