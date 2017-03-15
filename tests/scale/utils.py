import contextlib
import json
import os
import re
import subprocess
from six.moves import urllib
from dcos import http, util, config
from shakedown import run_command_on_master

def file_dir():
    """Gets the path to the shakedown dcos scale directory"""

    return os.path.dirname(os.path.realpath(__file__))


# should be in shakedown
def get_resource(resource):
    """
    :param resource: optional filename or http(s) url
    for the application or group resource
    :type resource: str
    :returns: resource
    :rtype: dict
    """
    resource = "{}/{}".format(file_dir(), resource)
    if resource is not None:
        if os.path.isfile(resource):
            with util.open_file(resource) as resource_file:
                return util.load_json(resource_file)
        else:
            try:
                http.silence_requests_warnings()
                req = http.get(resource)
                if req.status_code == 200:
                    data = b''
                    for chunk in req.iter_content(1024):
                        data += chunk
                    return util.load_jsons(data.decode('utf-8'))
                else:
                    raise Exception
            except Exception:
                raise DCOSException(
                    "Can't read from resource: {0}.\n"
                    "Please check that it exists.".format(resource))


@contextlib.contextmanager
def marathon_on_marathon(name='marathon-user'):
    """ Context manager for altering the marathon client for MoM
    :param name: service name of MoM to use
    :type name: str
    """

    toml_config_o = config.get_config()
    dcos_url = config.get_config_val('core.dcos_url', toml_config_o)
    service_name = 'service/{}/'.format(name)
    marathon_url = urllib.parse.urljoin(dcos_url, service_name)
    config.set_val('marathon.url', marathon_url)

    try:
        yield
    finally:
        # return config to previous state
        config.save(toml_config_o)
