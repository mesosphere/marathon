import contextlib
import pytest
import logging

from distutils.version import LooseVersion

from .service import service_available_predicate
from ..clients import marathon
from ..matcher import assert_that, eventually, has_len


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


def delete_app(app_id, force=True):
    marathon.create_client().remove_app(app_id, force=force)


def delete_app_wait(app_id, force=True):
    delete_app(app_id, force)
    deployment_wait(service=app_id)


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


def deployments_for(service_id=None, deployment_id=None):
    deployments = marathon.create_client().get_deployments()
    if deployment_id:
        filtered = [
            deployment for deployment in deployments
            if deployment_id == deployment["id"]
        ]
        return filtered
    elif service_id:
        filtered = [
            deployment for deployment in deployments
            if service_id in deployment['affectedApps'] or service_id in deployment['affectedPods']
        ]
        return filtered
    else:
        return deployments


def deployment_wait(service_id=None, deployment_id=None, wait_fixed=2000, max_attempts=60):
    """ Wait for a specific app/pod to deploy successfully. If no app/pod Id passed, wait for all
        current deployments to succeed. This inner matcher will retry fetching deployments
        after `wait_fixed` milliseconds but give up after `max_attempts` tries.
    """
    assert not all([service_id, deployment_id]), "Use either deployment_id or service_id, but not both."

    if deployment_id:
        logger.info("Waiting for the deployment_id {} to finish".format(deployment_id))
    elif service_id:
        logger.info('Waiting for {} to deploy successfully'.format(service_id))
    else:
        logger.info('Waiting for all current deployments to finish')

    assert_that(lambda: deployments_for(service_id, deployment_id),
                eventually(has_len(0), wait_fixed=wait_fixed, max_attempts=max_attempts))
