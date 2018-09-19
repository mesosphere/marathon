import os.path
import logging

from utils import make_id, get_resource
from os.path import join

logger = logging.getLogger(__name__)


def apps_dir():
    return os.path.dirname(os.path.abspath(__file__))


def load_app(app_def_file, app_id=None, parent_group="/"):
    """Loads an app definition from a json file and sets the app id."""
    app_path = os.path.join(apps_dir(), "{}.json".format(app_def_file))
    app = get_resource(app_path)

    if app_id is None:
        app['id'] = make_id(app_def_file, parent_group)
    else:
        app['id'] = join(parent_group, app_id)

    logger.info('Loaded an app definition with id={}'.format(app['id']))
    return app


def mesos_app(app_id=None, parent_group="/"):
    return load_app('mesos-app', app_id, parent_group)


def http_server(app_id=None, parent_group="/"):
    return load_app('http-server', app_id, parent_group)


def docker_http_server(app_id=None, parent_group="/"):
    return load_app('docker-http-server', app_id, parent_group)


def healthcheck_and_volume(app_id=None, parent_group="/"):
    return load_app('healthcheck-and-volume', app_id, parent_group)


def ucr_docker_http_server(app_id=None, parent_group="/"):
    return load_app('ucr-docker-http-server', parent_group="/")


def sleep_app(app_id=None, parent_group="/"):
    return load_app('sleep-app', app_id, parent_group)


def docker_nginx_ssl(app_id=None, parent_group="/"):
    return load_app('docker-nginx-ssl', app_id, parent_group)


def resident_docker_app(app_id=None, parent_group="/"):
    return load_app('resident-docker-app', app_id, parent_group)


def persistent_volume_app(app_id=None, parent_group="/"):
    return load_app('persistent-volume-app', app_id, parent_group)


def readiness_and_health_app(app_id=None, parent_group="/"):
    return load_app('readiness-and-health-app', app_id, parent_group)


def private_docker_app(app_id=None, parent_group="/"):
    return load_app('private-docker-app', app_id, parent_group)


def private_ucr_docker_app(app_id=None, parent_group="/"):
    return load_app('private-ucr-docker-app', app_id, parent_group)


def pinger_localhost_app(app_id=None, parent_group="/"):
    return load_app('pinger-localhost-app', app_id, parent_group)


def pinger_bridge_app(app_id=None, parent_group="/"):
    return load_app('pinger-bridge-app', app_id, parent_group)


def pinger_container_app(app_id=None, parent_group="/"):
    return load_app('pinger-container-app', app_id, parent_group)


def fake_framework(app_id=None, parent_group="/"):
    return load_app('fake-framework', app_id, parent_group)


def external_volume_mesos_app(app_id=None, parent_group="/"):
    return load_app('external-volume-mesos-app', app_id, parent_group)


def ipv6_healthcheck(app_id=None, parent_group="/"):
    """ The app uses netcat to listen on port. It uses alpine image which has netcat with ipv6 support by default.
        It uses command nc (shortcut for netcat) that runs for every new connection an echo command in shell.
        For more information about the nc options just run `docker run alpine nc --help`
    """
    return load_app('ipv6-healthcheck', app_id, parent_group)
