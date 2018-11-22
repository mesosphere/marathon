import contextlib
import pytest
import logging

from distutils.version import LooseVersion

from .service import service_available_predicate
from .spinner import time_wait
from ..clients import marathon


logger = logging.getLogger(__name__)

marathon_1_3 = pytest.mark.skipif('marathon_version_less_than("1.3")')
marathon_1_4 = pytest.mark.skipif('marathon_version_less_than("1.4")')
marathon_1_5 = pytest.mark.skipif('marathon_version_less_than("1.5")')


def marathon_version(client=None):
    client = client or marathon.create_client()
    about = client.get_about()
    # 1.3.9 or 1.4.0-RC8
    return LooseVersion(about.get("version"))


def marathon_version_less_than(version):
    return marathon_version() < LooseVersion(version)


def mom_version(name='marathon-user'):
    """Returns the version of marathon on marathon.
    """
    if service_available_predicate(name):
        with marathon_on_marathon(name) as client:
                return marathon_version(client)
    else:
        # We can either skip the corresponding test by returning False
        # or raise an exception.
        logger.warning('{} MoM not found. Version is None'.format(name))
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
        logger.warning('{} MoM not found. mom_version_less_than({}) is False'.format(name, version))
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


def delete_all_apps(force=True, client=None):
    client = client or marathon.create_client()
    client.remove_group("/", force=force)


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

    client = marathon.create_client(name)
    yield client
