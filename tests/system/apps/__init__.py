import os.path

from utils import make_id, get_resource


def apps_dir():
    return os.path.dirname(os.path.abspath(__file__))


def load_app(app_def_file, app_id=None):
    """Loads an app definition from a json file and sets the app id."""
    app_path = os.path.join(apps_dir(), "{}.json".format(app_def_file))
    app = get_resource(app_path)

    if app_id is None:
        app['id'] = make_id(app_def_file)
    else:
        app['id'] = app_id

    return app


def mesos_app(app_id=None):
    return load_app('mesos-app', app_id)


def http_server():
    return load_app('http-server')


def docker_http_server(app_id=None):
    return load_app('docker-http-server', app_id)


def healthcheck_and_volume():
    return load_app('healthcheck-and-volume')


def ucr_docker_http_server(app_id=None):
    return load_app('ucr-docker-http-server', app_id)


def sleep_app(app_id=None):
    app = load_app('sleep-app', app_id)
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
