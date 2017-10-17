""" Test using enterprise marathon on marathon (MoM-EE). The individual steps
    to install MoM-EE are well documented here:
    https://wiki.mesosphere.com/display/DCOS/MoM+1.4
"""

import apps
import common
import fixtures
import os
import pytest
import shakedown

from dcos import http
from shakedown import marathon
from urllib.parse import urljoin
from utils import get_resource


MOM_EE_NAME = 'marathon-user-ee'
MOM_EE_SERVICE_ACCOUNT = 'marathon_user_ee'
MOM_EE_SECRET_NAME = 'my-secret'

PRIVATE_KEY_FILE = 'private-key.pem'
PUBLIC_KEY_FILE = 'public-key.pem'

DEFAULT_MOM_IMAGES = {
    'MOM_EE_1.4': 'v1.4.7_1.9.8',
    'MOM_EE_1.3': '1.3.13_1.1.5'
}


def is_mom_ee_deployed():
    mom_ee_id = '/{}'.format(MOM_EE_NAME)
    client = marathon.create_client()
    apps = client.get_apps()
    return any(app['id'] == mom_ee_id for app in apps)


def remove_mom_ee():
    mom_ee_versions = [
        ('1.4', 'strict'),
        ('1.4', 'permissive'),
        ('1.4', 'disabled'),
        ('1.3', 'strict'),
        ('1.3', 'permissive'),
        ('1.3', 'disabled')
    ]
    for mom_ee in mom_ee_versions:
        endpoint = mom_ee_endpoint(mom_ee[0], mom_ee[1])
        if shakedown.service_available_predicate(endpoint):
            print('Removing {}...'.format(endpoint))
            with shakedown.marathon_on_marathon(name=endpoint):
                shakedown.delete_all_apps()

    client = marathon.create_client()
    client.remove_app(MOM_EE_NAME)
    shakedown.deployment_wait()
    print('Successfully removed {}'.format(MOM_EE_NAME))


def mom_ee_image(version):
    image_name = 'MOM_EE_{}'.format(version)
    try:
        os.environ[image_name]
    except:
        default_image = DEFAULT_MOM_IMAGES[image_name]
        print('No environment override found for MoM-EE  v{}. Using default image {}'.format(version, default_image))
        return default_image


def mom_ee_endpoint(version, security_mode):
    # '1.3', 'permissive' -> marathon-user-ee-permissive-1-3
    return '{}-{}-{}'.format(MOM_EE_NAME, security_mode, version.replace('.', '-'))


def assert_mom_ee(version, security_mode='permissive'):
    ensure_prerequisites_installed()
    ensure_service_account()
    ensure_permissions()
    ensure_secret(strict=True if security_mode == 'strict' else False)
    ensure_docker_credentials()

    # Deploy MoM-EE in permissive mode
    app_def_file = '{}/mom-ee-{}-{}.json'.format(fixtures.fixtures_dir(), security_mode, version)
    assert os.path.isfile(app_def_file), "Couldn't find appropriate MoM-EE definition: {}".format(app_def_file)

    image = mom_ee_image(version)
    print('Deploying {} definition with {} image'.format(app_def_file, image))

    app_def = get_resource(app_def_file)
    app_def['container']['docker']['image'] = 'mesosphere/marathon-dcos-ee:{}'.format(image)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    shakedown.wait_for_service_endpoint(mom_ee_endpoint(version, security_mode))


# strict security mode
@pytest.mark.skipif('shakedown.required_private_agents(2)')
@pytest.mark.skipif("shakedown.ee_version() != 'strict'")
@pytest.mark.parametrize("version,security_mode", [
    ('1.4', 'strict'),
    ('1.3', 'strict')
])
def test_strict_mom_ee(version, security_mode):
    assert_mom_ee(version, security_mode)
    assert simple_sleep_app(mom_ee_endpoint(version, security_mode))


# permissive security mode
@pytest.mark.skipif('shakedown.required_private_agents(2)')
@pytest.mark.skipif("shakedown.ee_version() != 'permissive'")
@pytest.mark.parametrize("version,security_mode", [
    ('1.4', 'permissive'),
    ('1.4', 'disabled'),
    ('1.3', 'permissive'),
    ('1.3', 'disabled')
])
def test_permissive_mom_ee(version, security_mode):
    assert_mom_ee(version, security_mode)
    assert simple_sleep_app(mom_ee_endpoint(version, security_mode))


# disabled security mode
@pytest.mark.skipif('shakedown.required_private_agents(2)')
@pytest.mark.skipif("shakedown.ee_version() != 'disabled'")
@pytest.mark.parametrize("version,security_mode", [
    ('1.4', 'disabled'),
    ('1.3', 'disabled')
])
def test_disabled_mom_ee(version, security_mode):
    assert_mom_ee(version, security_mode)
    assert simple_sleep_app(mom_ee_endpoint(version, security_mode))


def simple_sleep_app(name):
    # Deploy a simple sleep app in the MoM-EE
    with shakedown.marathon_on_marathon(name=name):
        client = marathon.create_client()

        app_def = apps.sleep_app()
        client.add_app(app_def)
        shakedown.deployment_wait()

        tasks = shakedown.get_service_task(name, app_def["id"].lstrip("/"))
        print('MoM-EE tasks: {}'.format(tasks))
        return tasks is not None


def ensure_prerequisites_installed():
    if not common.is_enterprise_cli_package_installed():
        common.install_enterprise_cli_package()
    assert common.is_enterprise_cli_package_installed()


def ensure_service_account():
    if common.has_service_account(MOM_EE_SERVICE_ACCOUNT):
        common.delete_service_account(MOM_EE_SERVICE_ACCOUNT)
    common.create_service_account(MOM_EE_SERVICE_ACCOUNT, PRIVATE_KEY_FILE, PUBLIC_KEY_FILE)
    assert common.has_service_account(MOM_EE_SERVICE_ACCOUNT)


def ensure_permissions():
    common.set_service_account_permissions(MOM_EE_SERVICE_ACCOUNT)

    url = urljoin(shakedown.dcos_url(), 'acs/api/v1/acls/dcos:superuser/users/{}'.format(MOM_EE_SERVICE_ACCOUNT))
    req = http.get(url)
    assert req.json()['array'][0]['url'] == '/acs/api/v1/acls/dcos:superuser/users/{}/full'.format(MOM_EE_SERVICE_ACCOUNT), \
        "Service account permissions couldn't be set"


def ensure_secret(strict=False):
    if common.has_secret(MOM_EE_SECRET_NAME):
        common.delete_secret(MOM_EE_SECRET_NAME)
    common.create_sa_secret(MOM_EE_SECRET_NAME, MOM_EE_SERVICE_ACCOUNT, strict)
    assert common.has_secret(MOM_EE_SECRET_NAME)


def ensure_docker_credentials():
    # Docker username and password should be passed  as environment variables `DOCKER_HUB_USERNAME`
    # and `DOCKER_HUB_PASSWORD` (usually by jenkins)
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']

    common.create_docker_credentials_file(username, password)
    common.copy_docker_credentials_file(shakedown.get_private_agents())


def cleanup():
    if is_mom_ee_deployed():
        remove_mom_ee()
    if common.has_service_account(MOM_EE_SERVICE_ACCOUNT):
        common.delete_service_account(MOM_EE_SERVICE_ACCOUNT)
    if common.has_secret(MOM_EE_SECRET_NAME):
        common.delete_secret(MOM_EE_SECRET_NAME)


def setup_function(function):
    if is_mom_ee_deployed():
        remove_mom_ee()


# An additional cleanup in case one/more tests failed and left mom-ee running
def teardown_module(module):
    cleanup()
