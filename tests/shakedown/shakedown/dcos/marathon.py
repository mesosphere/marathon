from distutils.version import LooseVersion
from dcos import marathon, config
from shakedown.dcos.spinner import *
from shakedown.dcos.service import service_available_predicate
from shakedown import *
from six.moves import urllib

import pytest


marathon_1_3 = pytest.mark.skipif('marthon_version_less_than("1.3")')
marathon_1_4 = pytest.mark.skipif('marthon_version_less_than("1.4")')
marathon_1_5 = pytest.mark.skipif('marthon_version_less_than("1.5")')


def marathon_leader_ip():
    """Returns the private IP of the marathon leader.
    """
    return dcos_dns_lookup('marathon.mesos')[0]['ip']


def marathon_version():
    client = marathon.create_client()
    about = client.get_about()
    # 1.3.9 or 1.4.0-RC8
    return LooseVersion(about.get("version"))


def marthon_version_less_than(version):
    return marathon_version() < LooseVersion(version)


def mom_version(name='marathon-user'):
    """Returns the version of marathon on marathon.
    """
    if service_available_predicate(name):
        with marathon_on_marathon(name):
                return marathon_version()
    else:
        # We can either skip the corresponding test by returning False
        # or raise an exception.
        print('WARN: {} MoM not found. Version is None'.format(name))
        return None


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
        return mom_version() < LooseVersion(version)
    else:
        # We can either skip the corresponding test by returning False
        # or raise an exception.
        print('WARN: {} MoM not found. mom_version_less_than({}) is False'.format(name, version))
        return False


def deployment_predicate(app_id=None):
    return len(marathon.create_client().get_deployments(app_id)) == 0


def deployment_wait(timeout=120, app_id=None):
    time_wait(lambda: deployment_predicate(app_id),
              timeout)


def delete_app(app_id, force=True):
    marathon.create_client().remove_app(app_id, force=force)


def delete_app_wait(app_id, force=True):
    delete_app(app_id, force)
    deployment_wait(app_id=app_id)


def delete_all_apps(force=True):
    marathon.create_client().remove_group("/", force=force)


def delete_all_apps_wait(force=True):
    delete_all_apps(force=force)
    deployment_wait()


def is_app_healthy(app_id):
    app = marathon.create_client().get_app(app_id)
    if app["healthChecks"]:
        return app["tasksHealthy"] == app["instances"]
    else:
        return app["tasksRunning"] == app["instances"]


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
