import os.path
import uuid

from utils import make_id, get_resource


def apps_dir():
    return os.path.dirname(os.path.abspath(__file__))


def load_app(app_name):
    app_path = os.path.join(apps_dir(), "{}.json".format(app_name))
    app = get_resource(app_path)
    app['id'] = make_id(app_name)
    return app


def mesos_app():
    return load_app('mesos-app')


def http_server():
    return load_app('http-server')


def docker_http_server():
    return load_app('docker-http-server')


def healthcheck_and_volume():
    return load_app('healthcheck-and-volume')


def ucr_docker_http_server():
    return load_app('ucr-docker-http-server')


def sleep_app(app_id=None):
    if app_id is None:
        app_id = '/sleep-{}'.format(uuid.uuid4().hex)
    app = load_app('sleep-app')
    app['id'] = app_id
    return app


def docker_nginx_ssl():
    return load_app('docker-nginx-ssl')


def resident_docker_app():
    return load_app('resident-docker-app')


def persistent_volume_app():
    return load_app('persistent-volume-app')


def readiness_and_health_app():
    return load_app('readiness-and-health-app')


def private_docker_app():
    return load_app('private-docker-app')


def private_ucr_docker_app():
    return load_app('private-ucr-docker-app')


def pinger_localhost_app():
    return load_app('pinger-localhost-app')


def pinger_bridge_app():
    return load_app('pinger-bridge-app')


def pinger_container_app():
    return load_app('pinger-container-app')


def fake_framework():
    return load_app('fake-framework')


def external_volume_mesos_app():
    return load_app('external-volume-mesos-app')


def ipv6_healthcheck():
    """ The app uses netcat to listen on port. It uses alpine image which has netcat with ipv6 support by default.
        It uses command nc (shortcut for netcat) that runs for every new connection an echo command in shell.
        For more information about the nc options just run `docker run alpine nc --help`
    """
    return load_app('ipv6-healthcheck')

def faultdomain_app(region=None, zone=None, instances=1, constraints=[]):
    """
    This is a dynamic app definition based on the faultdomain-base-app. It modifies it by appending
    the name, zone and region configuration as given

    :param region: The region to start this app in
    :param zone: The zone to start this app in
    :param instances: The number of instances in this app
    :param constraints: Other constraints to append
    :return: Returns the App Definition
    """
    app = load_app('faultdomain-base-app')
    app['instances'] = instances

    # Append region constraint
    if region is not None:
        app['constraints'] += [
            ["@region", "IS", str(region)]
        ]

    # Append zone constraint
    if zone is not None:
        app['constraints'] += [
            ["@zone", "IS", str(zone)]
        ]

    # Append misc constraints
    app['constraints'] += constraints

    return app