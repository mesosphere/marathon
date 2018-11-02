import os
import requests
import uuid

from shakedown import util
from shakedown.clients.authentication import dcos_acs_token, DCOSAcsAuth
from shakedown.clients.rpcclient import verify_ssl
from shakedown.errors import DCOSException
from os.path import join


def make_id(app_id_prefix=None, parent_group="/"):
    app_id = f'{app_id_prefix}-{uuid.uuid4().hex}' if app_id_prefix else str(uuid.uuid4().hex)
    return join(parent_group, app_id)


# should be in shakedown
def get_resource(resource):
    """:param resource: optional filename or http(s) url for the application or group resource
       :type resource: str
       :returns: resource
       :rtype: dict
    """

    if resource is None:
        return None

    if os.path.isfile(resource):
        with util.open_file(resource) as resource_file:
                return util.load_json(resource_file)
    else:
        try:
            auth = DCOSAcsAuth(dcos_acs_token())
            req = requests.get(resource, auth=auth, verify=verify_ssl())
            if req.status_code == 200:
                return req.json()
            else:
                raise Exception
        except Exception:
            raise DCOSException("Can't read from resource: {0}. Please check that it exists.".format(resource))
