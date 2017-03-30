import contextlib
import json
import os
import re
import subprocess
import pytest

from six.moves import urllib
from dcos import http, util, config
from dcos.errors import DCOSException
from distutils.version import LooseVersion
from shakedown import (service_available_predicate, marathon_version)


def fixture_dir():
    """Gets the path to the shakedown dcos fixture directory"""

    return "{}/fixtures".format(os.path.dirname(os.path.realpath(__file__)))


# should be in shakedown
def get_resource(resource):
    """
    :param resource: optional filename or http(s) url
    for the application or group resource
    :type resource: str
    :returns: resource
    :rtype: dict
    """
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


def parse_json(response):
    return response.json()


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


mom_1_4 = pytest.mark.skipif('mom_version_less_than("1.4")')
mom_1_4_2 = pytest.mark.skipif('mom_version_less_than("1.4.2")')


def mom_version_less_than(version, name='marathon-user'):
    """ Returns True if MoM with the given {name} exists and has a version less
        than {version}. Note that if MoM does not exist False is returned.

        :param version: required version
        :type: string
        :param name: MoM name, default is 'marathon-user'
        :type: string
        :return: True if version < MoM version
        :rtype: bool
    """
    if service_available_predicate(name):
        with marathon_on_marathon(name):
            return marathon_version() < LooseVersion(version)
    else:
        # We can either skip the corresponding test by returning False
        # or raise an exception.
        print('WARN: {} MoM not found. mom_version_less_than({}) is False'.format(name, version))
        return False
