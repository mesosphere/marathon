import json
import os
import pytest
import shakedown
import shlex
import time
import uuid

from datetime import timedelta
from dcos import http, mesos
from distutils.version import LooseVersion
from shakedown import marathon
from urllib.parse import urljoin
from dcos.errors import DCOSHTTPException


marathon_1_3 = pytest.mark.skipif('marthon_version_less_than("1.3")')
marathon_1_4 = pytest.mark.skipif('marthon_version_less_than("1.4")')
marathon_1_5 = pytest.mark.skipif('marthon_version_less_than("1.5")')


def ignore_exception(exc):
    """Used with @retrying.retry to ignore exceptions in a retry loop.
       ex.  @retrying.retry( retry_on_exception=ignore_exception)
       It does verify that the object passed is an exception
    """
    return isinstance(exc, Exception)


def ignore_exception(exc):
    """ Used with @retrying.retry to igmore exceptions in a retry loop.
    ex.  @retrying.retry( retry_on_exception=ignore_exception)
    It does verify that the object passed is an exception
    """
    return isinstance(exc, Exception)


def constraints(name, operator, value=None):
    constraints = [name, operator]
    if value is not None:
        constraints.append(value)
    return [constraints]


def pod_constraints(name, operator, value=None):
    constraints = {
        'fieldName': name,
        'operator': operator,
        'value': value
    }

    return constraints


def unique_host_constraint():
    return constraints('hostname', 'UNIQUE')


def assert_http_code(url, http_code='200'):
    cmd = r'curl -s -o /dev/null -w "%{http_code}"'
    cmd = cmd + ' {}'.format(url)
    status, output = shakedown.run_command_on_master(cmd)

    assert status, "{} failed".format(cmd)
    assert output == http_code, "Got {} status code".format(output)


def add_role_constraint_to_app_def(app_def, roles=['*']):
    """Roles are a comma-delimited list. Acceptable roles include:
           '*'
           'slave_public'
           '*, slave_public'
    """
    app_def['acceptedResourceRoles'] = roles
    return app_def


def pin_to_host(app_def, host):
    app_def['constraints'] = constraints('hostname', 'LIKE', host)


def pin_pod_to_host(app_def, host):
    app_def['scheduling']['placement']['constraints'].append(pod_constraints('hostname', 'LIKE', host))


def health_check(path='/', protocol='HTTP', port_index=0, failures=1, timeout=2):
    return {
        'protocol': protocol,
        'path': path,
        'timeoutSeconds': timeout,
        'intervalSeconds': 1,
        'maxConsecutiveFailures': failures,
        'portIndex': port_index
    }


def external_volume_mesos_app(volume_name=None):
    if volume_name is None:
        volume_name = 'marathon-si-test-vol-{}'.format(uuid.uuid4().hex)

    return


def command_health_check(command='true', failures=1, timeout=2):
    return {
        'protocol': 'COMMAND',
        'command': {'value': command},
        'timeoutSeconds': timeout,
        'intervalSeconds': 2,
        'maxConsecutiveFailures': failures
    }


def cluster_info(mom_name='marathon-user'):
    print("DC/OS: {}, in {} mode".format(shakedown.dcos_version(), shakedown.ee_version()))
    agents = shakedown.get_private_agents()
    print("Agents: {}".format(len(agents)))
    client = marathon.create_client()
    about = client.get_about()
    print("Marathon version: {}".format(about.get("version")))

    if shakedown.service_available_predicate(mom_name):
        with shakedown.marathon_on_marathon(mom_name):
            try:
                client = marathon.create_client()
                about = client.get_about()
                print("Marathon MoM version: {}".format(about.get("version")))
            except:
                print("Marathon MoM not present")
    else:
        print("Marathon MoM not present")


def delete_all_apps():
    client = marathon.create_client()
    apps = client.get_apps()
    for app in apps:
        if app['id'] == '/marathon-user':
            print('WARNING: not removing marathon-user, because it is special')
        else:
            client.remove_app(app['id'], True)


def stop_all_deployments(noisy=False):
    client = marathon.create_client()
    deployments = client.get_deployments()
    for deployment in deployments:
        try:
            client.stop_deployment(deployment['id'])
        except Exception as e:
            if noisy:
                print(e)


def delete_all_apps_wait():
    delete_all_apps()
    shakedown.deployment_wait(timedelta(minutes=5).total_seconds())


def delete_all_groups():
    client = marathon.create_client()
    groups = client.get_groups()
    for group in groups:
        client.remove_group(group["id"])


def clean_up_marathon():
    try:
        stop_all_deployments()
        clear_pods()
        delete_all_apps_wait()
        delete_all_groups()
    except Exception as e:
        print(e)


def ip_other_than_mom():
    mom_ip = ip_of_mom()

    agents = shakedown.get_private_agents()
    for agent in agents:
        if agent != mom_ip:
            return agent

    return None


def ip_of_mom():
    service_ips = shakedown.get_service_ips('marathon', 'marathon-user')
    for mom_ip in service_ips:
        return mom_ip


def ensure_mom():
    if not is_mom_installed():
        # if there is an active deployment... wait for it.
        # it is possible that mom is currently in the process of being uninstalled
        # in which case it will not report as installed however install will fail
        # until the deployment is finished.
        shakedown.deployment_wait()

        try:
            shakedown.install_package_and_wait('marathon')
            shakedown.deployment_wait()
        except:
            pass

        if not shakedown.wait_for_service_endpoint('marathon-user'):
            print('ERROR: Timeout waiting for endpoint')


def is_mom_installed():
    return shakedown.package_installed('marathon')


def restart_master_node():
    """Restarts the master node."""

    shakedown.run_command_on_master("sudo /sbin/shutdown -r now")


def systemctl_master(command='restart'):
    shakedown.run_command_on_master('sudo systemctl {} dcos-mesos-master'.format(command))


def save_iptables(host):
    shakedown.run_command_on_agent(
        host,
        'if [ ! -e iptables.rules ] ; then sudo iptables -L > /dev/null && sudo iptables-save > iptables.rules ; fi')


def restore_iptables(host):
    shakedown.run_command_on_agent(
        host, 'if [ -e iptables.rules ]; then sudo iptables-restore < iptables.rules && rm iptables.rules ; fi')


def block_port(host, port, direction='INPUT'):
    shakedown.run_command_on_agent(host, 'sudo iptables -I {} -p tcp --dport {} -j DROP'.format(direction, port))


def wait_for_task(service, task, timeout_sec=120):
    """Waits for a task which was launched to be launched"""

    now = time.time()
    future = now + timeout_sec

    while now < future:
        response = None
        try:
            response = shakedown.get_service_task(service, task)
        except:
            pass

        if response is not None and response['state'] == 'TASK_RUNNING':
            return response
        else:
            time.sleep(5)
            now = time.time()

    return None


def clear_pods():
    try:
        client = marathon.create_client()
        pods = client.list_pod()
        for pod in pods:
            client.remove_pod(pod["id"], True)
        shakedown.deployment_wait()
    except:
        pass


def get_pod_tasks(pod_id):
    pod_id = pod_id.lstrip('/')
    pod_tasks = []
    tasks = shakedown.get_marathon_tasks()
    for task in tasks:
        if task['discovery']['name'] == pod_id:
            pod_tasks.append(task)

    return pod_tasks


def marathon_version():
    client = marathon.create_client()
    about = client.get_about()
    # 1.3.9 or 1.4.0-RC8
    return LooseVersion(about.get("version"))


def marthon_version_less_than(version):
    return marathon_version() < LooseVersion(version)


dcos_1_10 = pytest.mark.skipif('dcos_version_less_than("1.10")')
dcos_1_9 = pytest.mark.skipif('dcos_version_less_than("1.9")')
dcos_1_8 = pytest.mark.skipif('dcos_version_less_than("1.8")')
dcos_1_7 = pytest.mark.skipif('dcos_version_less_than("1.7")')


def dcos_canonical_version():
    version = shakedown.dcos_version().replace('-dev', '')
    return LooseVersion(version)


def dcos_version_less_than(version):
    return dcos_canonical_version() < LooseVersion(version)


def assert_app_tasks_running(client, app_def):
    app_id = app_def['id']
    instances = app_def['instances']

    app = client.get_app(app_id)
    assert app['tasksRunning'] == instances


def assert_app_tasks_healthy(client, app_def):
    app_id = app_def['id']
    instances = app_def['instances']

    app = client.get_app(app_id)
    assert app['tasksHealthy'] == instances


def get_marathon_leader_not_on_master_leader_node():
    marathon_leader = shakedown.marathon_leader_ip()
    master_leader = shakedown.master_leader_ip()
    print('marathon: {}'.format(marathon_leader))
    print('leader: {}'.format(master_leader))

    if marathon_leader == master_leader:
        delete_marathon_path('v2/leader')
        shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
        new_leader = shakedown.marathon_leader_ip()
        assert new_leader != marathon_leader, "A new Marathon leader has not been elected"
        marathon_leader = new_leader
        print('switched leader to: {}'.format(marathon_leader))

    return marathon_leader


def docker_env_not_set():
    return 'DOCKER_HUB_USERNAME' not in os.environ or 'DOCKER_HUB_PASSWORD' not in os.environ


#############
#  moving to shakedown  START
#############


def install_enterprise_cli_package():
    """Install `dcos-enterprise-cli` package. It is required by the `dcos security`
       command to create secrets, manage service accounts etc.
    """
    print('Installing dcos-enterprise-cli package')
    stdout, stderr, return_code = shakedown.run_dcos_command('package install dcos-enterprise-cli --cli --yes')
    assert return_code == 0, "Failed to install dcos-enterprise-cli package"


def is_enterprise_cli_package_installed():
    """Returns `True` if `dcos-enterprise-cli` package is installed."""
    stdout, stderr, return_code = shakedown.run_dcos_command('package list --json')
    result_json = json.loads(stdout)
    return any(cmd['name'] == 'dcos-enterprise-cli' for cmd in result_json)


def create_docker_pull_config_json(username, password):
    """Create a Docker config.json represented using Python data structures.

       :param username: username for a private Docker registry
       :param password: password for a private Docker registry
       :return: Docker config.json
    """
    print('Creating a config.json content for dockerhub username {}'.format(username))

    import base64
    auth_hash = base64.b64encode('{}:{}'.format(username, password).encode()).decode()

    return {
        "auths": {
            "https://index.docker.io/v1/": {
                "auth": auth_hash
            }
        }
    }


def create_docker_credentials_file(username, password, file_name='docker.tar.gz'):
    """Create a docker credentials file. Docker username and password are used to create
       a `{file_name}` with `.docker/config.json` containing the credentials.

       :param file_name: credentials file name `docker.tar.gz` by default
       :type command: str
    """

    print('Creating a tarball {} with json credentials for dockerhub username {}'.format(file_name, username))
    config_json_filename = 'config.json'

    config_json = create_docker_pull_config_json(username, password)

    # Write config.json to file
    with open(config_json_filename, 'w') as f:
        json.dump(config_json, f, indent=4)

    try:
        # Create a docker.tar.gz
        import tarfile
        with tarfile.open(file_name, 'w:gz') as tar:
            tar.add(config_json_filename, arcname='.docker/config.json')
            tar.close()
    except Exception as e:
        print('Failed to create a docker credentils file {}'.format(e))
        raise e
    finally:
        os.remove(config_json_filename)


def copy_docker_credentials_file(agents, file_name='docker.tar.gz'):
    """Create and copy docker credentials file to passed `{agents}`. Used to access private
       docker repositories in tests. File is removed at the end.

       :param agents: list of agent IPs to copy the file to
       :type agents: list
    """

    assert os.path.isfile(file_name), "Failed to upload credentials: file {} not found".format(file_name)

    # Upload docker.tar.gz to all private agents
    try:
        print('Uploading tarball with docker credentials to all private agents...')
        for agent in agents:
            print("Copying docker credentials to {}".format(agent))
            shakedown.copy_file_to_agent(agent, file_name)
    except Exception as e:
        print('Failed to upload {} to agent: {}'.format(file_name, agent))
        raise e
    finally:
        os.remove(file_name)


def has_secret(secret_name):
    """Returns `True` if the secret with given name exists in the vault.
       This method uses `dcos security secrets` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param secret_name: secret name
       :type secret_name: str
    """
    stdout, stderr, return_code = shakedown.run_dcos_command('security secrets list / --json')
    if stdout:
        result_json = json.loads(stdout)
        return secret_name in result_json
    return False


def delete_secret(secret_name):
    """Delete a secret with a given name from the vault.
       This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param secret_name: secret name
       :type secret_name: str
    """
    print('Removing existing secret {}'.format(secret_name))
    stdout, stderr, return_code = shakedown.run_dcos_command('security secrets delete {}'.format(secret_name))
    assert return_code == 0, "Failed to remove existing secret"


def create_secret(name, value=None, description=None):
    """Create a secret with a passed `{name}` and optional `{value}`.
       This method uses `dcos security secrets` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param name: secret name
       :type name: str
       :param value: optional secret value
       :type value: str
       :param description: option secret description
       :type description: str
    """
    print('Creating new secret {}:{}'.format(name, value))

    value_opt = '-v {}'.format(shlex.quote(value)) if value else ''
    description_opt = '-d "{}"'.format(description) if description else ''

    stdout, stderr, return_code = shakedown.run_dcos_command('security secrets create {} {} "{}"'.format(
        value_opt,
        description_opt,
        name), print_output=True)
    assert return_code == 0, "Failed to create a secret"


def create_sa_secret(secret_name, service_account, strict=False, private_key_filename='private-key.pem'):
    """Create an sa-secret with a given private key file for passed service account in the vault. Both
       (service account and secret) should share the same key pair. `{strict}` parameter should be
       `True` when creating a secret in a `strict` secure cluster. Private key file will be removed
       after secret is successfully created.
       This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param secret_name: secret name
       :type secret_name: str
       :param service_account: service account name
       :type service_account: str
       :param strict: `True` is this a `strict` secure cluster
       :type strict: bool
       :param private_key_filename: private key file name
       :type private_key_filename: str
    """
    assert os.path.isfile(private_key_filename), "Failed to create secret: private key not found"

    print('Creating new sa-secret {} for service-account: {}'.format(secret_name, service_account))
    strict_opt = '--strict' if strict else ''
    stdout, stderr, return_code = shakedown.run_dcos_command('security secrets create-sa-secret {} {} {} {}'.format(
        strict_opt,
        private_key_filename,
        service_account,
        secret_name))

    os.remove(private_key_filename)
    assert return_code == 0, "Failed to create a secret"


def has_service_account(service_account):
    """Returns `True` if a service account with a given name already exists.
       This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param service_account: service account name
       :type service_account: str
    """
    stdout, stderr, return_code = shakedown.run_dcos_command('security org service-accounts show --json')
    result_json = json.loads(stdout)
    return service_account in result_json


def delete_service_account(service_account):
    """Removes an existing service account. This method uses `dcos security org`
       command and assumes that `dcos-enterprise-cli` package is installed.

       :param service_account: service account name
       :type service_account: str
    """
    print('Removing existing service account {}'.format(service_account))
    stdout, stderr, return_code = \
        shakedown.run_dcos_command('security org service-accounts delete {}'.format(service_account))
    assert return_code == 0, "Failed to create a service account"


def create_service_account(service_account, private_key_filename='private-key.pem',
                           public_key_filename='public-key.pem', account_description='SI test account'):
    """Create new private and public key pair and use them to add a new service
       with a give name. Public key file is then removed, however private key file
       is left since it might be used to create a secret. If you don't plan on creating
       a secret afterwards, please remove it manually.
       This method uses `dcos security org` command and assumes that `dcos-enterprise-cli`
       package is installed.

       :param service_account: service account name
       :type service_account: str
       :param private_key_filename: optional private key file name
       :type private_key_filename: str
       :param public_key_filename: optional public key file name
       :type public_key_filename: str
       :param account_description: service account description
       :type account_description: str
    """
    print('Creating a key pair for the service account')
    shakedown.run_dcos_command('security org service-accounts keypair {} {}'.format(
        private_key_filename, public_key_filename))
    assert os.path.isfile(private_key_filename), "Private key of the service account key pair not found"
    assert os.path.isfile(public_key_filename), "Public key of the service account key pair not found"

    print('Creating {} service account'.format(service_account))
    stdout, stderr, return_code = shakedown.run_dcos_command(
        'security org service-accounts create -p {} -d "{}" {}'.format(
            public_key_filename, account_description, service_account))

    os.remove(public_key_filename)
    assert return_code == 0


def set_service_account_permissions(service_account, resource='dcos:superuser', action='full'):
    """Set permissions for given `{service_account}` for passed `{resource}` with
       `{action}`. For more information consult the DC/OS documentation:
       https://docs.mesosphere.com/1.9/administration/id-and-access-mgt/permissions/user-service-perms/
    """
    print('Granting {} permissions to {}/users/{}'.format(action, resource, service_account))
    url = urljoin(shakedown.dcos_url(), 'acs/api/v1/acls/{}/users/{}/{}'.format(resource, service_account, action))
    req = http.put(url)
    assert req.status_code == 204, 'Failed to grant permissions to the service account: {}, {}'.format(req, req.text)


def add_dcos_marathon_root_user_acls():
    try:
        set_service_account_permissions('dcos_marathon', resource='dcos:mesos:master:task:user:root', action='create')
    except DCOSHTTPException as e:
        if (e.response.status_code == 409):
            print('Service account dcos_marathon already has "dcos:mesos:master:task:user:root" permissions set')
        else:
            raise
    except:
        raise


def get_marathon_endpoint(path, marathon_name='marathon'):
    """Returns the url for the marathon endpoint."""
    return shakedown.dcos_url_path('service/{}/{}'.format(marathon_name, path))


def http_get_marathon_path(name, marathon_name='marathon'):
    """Invokes HTTP GET for marathon url with name.
       For example, name='ping': http GET {dcos_url}/service/marathon/ping
    """
    url = get_marathon_endpoint(name, marathon_name)
    headers = {'Accept': '*/*'}
    return http.get(url, headers=headers)


# PR added to dcos-cli (however it takes weeks)
# https://github.com/dcos/dcos-cli/pull/974
def delete_marathon_path(name, marathon_name='marathon'):
    """Invokes HTTP DELETE for marathon url with name.
       For example, name='v2/leader': http GET {dcos_url}/service/marathon/v2/leader
    """
    url = get_marathon_endpoint(name, marathon_name)
    return http.delete(url)


def multi_master():
    """Returns True if this is a multi master cluster. This is useful in
       using pytest skipif when testing single master clusters such as:
       `pytest.mark.skipif('multi_master')` which will skip the test if
       the number of masters is > 1.
    """
    # reverse logic (skip if multi master cluster)
    return len(shakedown.get_all_masters()) > 1


def __get_all_agents():
    """Provides all agent json in the cluster which can be used for filtering"""

    client = mesos.DCOSClient()
    agents = client.get_state_summary()['slaves']
    return agents


def agent_hostname_by_id(agent_id):
    """Given a agent_id provides the agent ip"""
    for agent in __get_all_agents():
        if agent['id'] == agent_id:
            return agent['hostname']

    return None
