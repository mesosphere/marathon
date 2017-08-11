import os.path

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


def ucr_docker_http_server():
    return load_app('ucr-docker-http-server')


def sleep_app():
    return load_app('sleep-app')


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
