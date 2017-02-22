""" Test using enterprise marathon on marathon (MoM-EE). The individual steps
    to install MoM-EE are well documented here:
    https://wiki.mesosphere.com/display/DCOS/MoM+1.4
"""

import os

from common import *
from shakedown import *
from dcos import http

MOM_EE_NAME = 'marathon-user-ee'
MOM_EE_SERVICE_ACCOUNT = 'marathon_user_ee'
MOM_EE_SECRET_NAME = 'my-secret'

PRIVATE_KEY_FILE = 'private-key.pem'
PUBLIC_KEY_FILE = 'public-key.pem'

DEFAULT_MOM_IMAGES = {
    'MOM_EE_1.4': '1.4.1_1.9.7',
    'MOM_EE_1.3': '1.3.10_1.1.5'
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
        print('Removing {}...'.format(endpoint))
        if service_available_predicate(endpoint):
            with marathon_on_marathon(name=endpoint):
                delete_all_apps()

    client = marathon.create_client()
    client.remove_app(MOM_EE_NAME)
    deployment_wait()
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
    app_def_file = '{}/mom-ee-{}-{}.json'.format(fixture_dir(), security_mode, version)
    assert os.path.isfile(app_def_file), "Couldn't find appropriate MoM-EE definition: {}".format(app_def_file)

    image = mom_ee_image(version)
    print('Deploying {} definition with {} image'.format(app_def_file, image))

    app_def = get_resource(app_def_file)
    app_def['container']['docker']['image'] = 'mesosphere/marathon-dcos-ee:{}'.format(image)

    client = marathon.create_client()
    client.add_app(app_def)
    deployment_wait()
    wait_for_service_endpoint(mom_ee_endpoint(version, security_mode))


@pytest.mark.parametrize("version,security_mode", [
pytest.mark.skipif("ee_version() != 'strict'")(('1.4', 'strict')),
        ('1.4', 'permissive'),
        ('1.4', 'disabled'),
pytest.mark.skipif("ee_version() != 'strict'")(('1.3', 'strict')),
        ('1.3', 'permissive'),
        ('1.3', 'disabled')
])
def test_mom_ee(version, security_mode):
    with clean_state():
        assert_mom_ee(version, security_mode)
        assert simple_sleep_app(mom_ee_endpoint(version, security_mode))


def simple_sleep_app(name):
    # Deploy a simple sleep app in the MoM-EE
    with marathon_on_marathon(name=name):
        client = marathon.create_client()

        app_id = uuid.uuid4().hex
        app_def = app(app_id)
        client.add_app(app_def)
        deployment_wait()

        tasks = get_service_task(name, app_id)
        print('MoM-EE tasks: {}'.format(tasks))
        return tasks is not None


def ensure_prerequisites_installed():
    if not is_enterprise_cli_package_installed():
        install_enterprise_cli_package()
    assert is_enterprise_cli_package_installed() == True


def ensure_service_account():
    if has_service_account(MOM_EE_SERVICE_ACCOUNT):
        delete_service_account(MOM_EE_SERVICE_ACCOUNT)
    create_service_account(MOM_EE_SERVICE_ACCOUNT, PRIVATE_KEY_FILE, PUBLIC_KEY_FILE)
    assert has_service_account(MOM_EE_SERVICE_ACCOUNT)


def ensure_permissions():
    set_service_account_permissions(MOM_EE_SERVICE_ACCOUNT)

    url = '{}acs/api/v1/acls/dcos:superuser/users/{}'.format(dcos_url(), MOM_EE_SERVICE_ACCOUNT)
    req = http.get(url)
    assert req.json()['array'][0]['url'] == '/acs/api/v1/acls/dcos:superuser/users/{}/full'.format(MOM_EE_SERVICE_ACCOUNT), "Service account permissions couldn't be set"


def ensure_secret(strict=False):
    if has_secret(MOM_EE_SECRET_NAME):
        delete_secret(MOM_EE_SECRET_NAME)
    create_secret(MOM_EE_SECRET_NAME, MOM_EE_SERVICE_ACCOUNT, strict)
    assert has_secret(MOM_EE_SECRET_NAME)


def ensure_docker_credentials():
    # Docker username and password should be passed  as environment variables `DOCKER_HUB_USERNAME`
    # and `DOCKER_HUB_PASSWORD` (usually by jenkins)
    assert 'DOCKER_HUB_USERNAME' in os.environ, "Couldn't find docker hub username. $DOCKER_HUB_USERNAME is not set"
    assert 'DOCKER_HUB_PASSWORD' in os.environ, "Couldn't find docker hub password. $DOCKER_HUB_PASSWORD is not set"

    username = os.environ['DOCKER_HUB_USERNAME']
    password = os.environ['DOCKER_HUB_PASSWORD']

    create_docker_credentials_file(username, password)
    copy_docker_credentials_file(get_private_agents())


@contextlib.contextmanager
def clean_state():
    if is_mom_ee_deployed():
        remove_mom_ee()
    assert not is_mom_ee_deployed()
    yield
    remove_mom_ee()
    delete_service_account(MOM_EE_SERVICE_ACCOUNT)
    delete_secret(MOM_EE_SECRET_NAME)
