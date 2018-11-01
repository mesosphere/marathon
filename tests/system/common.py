import asyncio
import json
import os
import pytest
import shlex
import time
import uuid
import retrying
import requests
import logging

from datetime import timedelta
from json.decoder import JSONDecodeError
from functools import lru_cache
from fixtures import get_ca_file
from shakedown.clients import mesos, marathon, authentication, dcos_url_path
from shakedown.clients.authentication import dcos_acs_token, DCOSAcsAuth
from shakedown.clients.rpcclient import verify_ssl
from shakedown.dcos import dcos_version, marathon_leader_ip, master_leader_ip
from shakedown.dcos.agent import get_private_agents
from shakedown.dcos.cluster import ee_version
from shakedown.dcos.command import run_command, run_command_on_agent, run_command_on_master
from shakedown.clients.cli import attached_cli, run_dcos_command
from shakedown.dcos.file import copy_file_to_agent
from shakedown.dcos.marathon import marathon_on_marathon
from shakedown.dcos.master import get_all_master_ips
from shakedown.dcos.package import install_package_and_wait, package_installed
from shakedown.dcos.service import get_marathon_tasks, get_service_ips, get_service_task, service_available_predicate
from shakedown.errors import DCOSException
from shakedown.matcher import assert_that, eventually, has_len
from precisely import equal_to

logger = logging.getLogger(__name__)

marathon_1_3 = pytest.mark.skipif('marathon_version_less_than("1.3")')
marathon_1_4 = pytest.mark.skipif('marathon_version_less_than("1.4")')
marathon_1_5 = pytest.mark.skipif('marathon_version_less_than("1.5")')
marathon_1_6 = pytest.mark.skipif('marathon_version_less_than("1.6")')
marathon_1_7 = pytest.mark.skipif('marathon_version_less_than("1.7")')


def ignore_exception(exc):
    """Used with @retrying.retry to ignore exceptions in a retry loop.
       ex.  @retrying.retry( retry_on_exception=ignore_exception)
       It does verify that the object passed is an exception
    """
    return isinstance(exc, Exception)


def ignore_provided_exception(toTest):
    """Used with @retrying.retry to ignore a specific exception in a retry loop.
       ex.  @retrying.retry( retry_on_exception=ignore_provided_exception(DCOSException))
       It does verify that the object passed is an exception
    """
    return lambda exc: isinstance(exc, toTest)


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


@retrying.retry(wait_fixed=1000, stop_max_attempt_number=60, retry_on_exception=ignore_exception)
def assert_http_code(url, http_code='200'):
    cmd = r'curl -s -o /dev/null -w "%{http_code}"'
    cmd = cmd + ' {}'.format(url)
    status, output = run_command_on_master(cmd)

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
    logger.info("DC/OS: %s, in %s mode", dcos_version(), ee_version())
    agents = get_private_agents()
    logger.info("Agents: %d", len(agents))
    client = marathon.create_client()
    about = client.get_about()
    logger.info("Marathon version: %s", about.get("version"))

    if service_available_predicate(mom_name):
        with marathon_on_marathon(mom_name) as client:
            try:
                about = client.get_about()
                logger.info("Marathon MoM version: {}".format(about.get("version")))
            except Exception:
                logger.info("Marathon MoM not present")
    else:
        logger.info("Marathon MoM not present")


def clean_up_marathon(parent_group="/", client=None):
    client = client or marathon.create_client()

    response = client.remove_group(parent_group, force=True)
    deployment_wait(deployment_id=response["deploymentId"])


def ip_other_than_mom():
    mom_ip = ip_of_mom()

    agents = get_private_agents()
    for agent in agents:
        if agent != mom_ip:
            return agent

    return None


def ip_of_mom():
    service_ips = get_service_ips('marathon', 'marathon-user')
    for mom_ip in service_ips:
        return mom_ip


def ensure_mom():
    if not is_mom_installed():
        # if there is an active deployment... wait for it.
        # it is possible that mom is currently in the process of being uninstalled
        # in which case it will not report as installed however install will fail
        # until the deployment is finished.
        deployment_wait()

        try:
            install_package_and_wait('marathon')
            deployment_wait(service_id='/marathon-user')
        except Exception:
            logger.exception('Error while waiting for MoM to deploy')
            pass

        if not wait_for_service_endpoint('marathon-user', path="ping"):
            logger.error('Timeout waiting for endpoint')


def is_mom_installed():
    return package_installed('marathon')


def restart_master_node():
    """Restarts the master node."""

    run_command_on_master("sudo /sbin/shutdown -r now")


def cpus_on_agent(hostname):
    """Detects number of cores on an agent"""
    status, output = run_command_on_agent(hostname, "cat /proc/cpuinfo | grep processor | wc -l", noisy=False)
    return int(output)


def systemctl_master(command='restart'):
    run_command_on_master('sudo systemctl {} dcos-mesos-master'.format(command))


def block_iptable_rules_for_seconds(host, port_number, sleep_seconds, block_input=True, block_output=True):
    """ For testing network partitions we alter iptables rules to block ports for some time.
        We do that as a single SSH command because otherwise it makes it hard to ensure that iptable rules are restored.
    """
    filename = 'iptables-{}.rules'.format(uuid.uuid4().hex)
    cmd = """
          if [ ! -e {backup} ] ; then sudo iptables-save > {backup} ; fi;
          {block}
          sleep {seconds};
          if [ -e {backup} ]; then sudo iptables-restore < {backup} && sudo rm {backup} ; fi
        """.format(backup=filename, seconds=sleep_seconds,
                   block=iptables_block_string(block_input, block_output, port_number))

    run_command_on_agent(host, cmd)


def iptables_block_string(block_input, block_output, port):
    """ Produces a string of iptables blocking command that can be executed on an agent. """
    block_input_str = "sudo iptables -I INPUT -p tcp --dport {} -j DROP;".format(port) if block_input else ""
    block_output_str = "sudo iptables -I OUTPUT -p tcp --dport {} -j DROP;".format(port) if block_output else ""
    return block_input_str + block_output_str


def wait_for_task(service, task, timeout_sec=120):
    """Waits for a task which was launched to be launched"""

    now = time.time()
    future = now + timeout_sec

    while now < future:
        response = None
        try:
            response = get_service_task(service, task)
        except Exception:
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
            deployment_wait(service_id=pod["id"])
    except Exception:
        pass


def get_pod_tasks(pod_id):
    pod_id = pod_id.lstrip('/')
    pod_tasks = []
    tasks = get_marathon_tasks()
    for task in tasks:
        if task['discovery']['name'] == pod_id:
            pod_tasks.append(task)

    return pod_tasks


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
    marathon_leader = marathon_leader_ip()
    master_leader = master_leader_ip()
    logger.info('marathon leader: {}'.format(marathon_leader))
    logger.info('mesos leader: {}'.format(master_leader))

    if marathon_leader == master_leader:
        delete_marathon_path('v2/leader')
        wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds(), path="ping")
        marathon_leader = assert_marathon_leadership_changed(marathon_leader)
        logger.info('switched leader to: {}'.format(marathon_leader))

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
    logger.info('Installing dcos-enterprise-cli package')
    with attached_cli():
        cmd = 'package install dcos-enterprise-cli --cli --yes'
        stdout, stderr, return_code = run_dcos_command(cmd, raise_on_error=True)


def is_enterprise_cli_package_installed():
    """Returns `True` if `dcos-enterprise-cli` package is installed."""
    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('package list --json')
        logger.info('package list command returned code:{}, stderr:{}, stdout: {}'.format(return_code, stderr, stdout))
        try:
            result_json = json.loads(stdout)
        except JSONDecodeError as error:
            raise DCOSException('Could not parse: "{}"'.format(stdout)) from error
        return any(cmd['name'] == 'dcos-enterprise-cli' for cmd in result_json)


def create_docker_pull_config_json(username, password):
    """Create a Docker config.json represented using Python data structures.

       :param username: username for a private Docker registry
       :param password: password for a private Docker registry
       :return: Docker config.json
    """
    logger.info('Creating a config.json content for dockerhub username {}'.format(username))

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

    logger.info('Creating a tarball {} with json credentials for dockerhub username {}'.format(file_name, username))
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
        logger.info('Failed to create a docker credentils file {}'.format(e))
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
        logger.info('Uploading tarball with docker credentials to all private agents...')
        for agent in agents:
            logger.info("Copying docker credentials to {}".format(agent))
            copy_file_to_agent(agent, file_name)
    except Exception as e:
        logger.info('Failed to upload {} to agent: {}'.format(file_name, agent))
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
    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('security secrets list / --json')
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
    logger.info('Removing existing secret {}'.format(secret_name))
    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('security secrets delete {}'.format(secret_name))
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
    logger.info('Creating new secret {}:{}'.format(name, value))

    value_opt = '-v {}'.format(shlex.quote(value)) if value else ''
    description_opt = '-d "{}"'.format(description) if description else ''

    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('security secrets create {} {} "{}"'.format(
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

    logger.info('Creating new sa-secret {} for service-account: {}'.format(secret_name, service_account))
    strict_opt = '--strict' if strict else ''
    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('security secrets create-sa-secret {} {} {} {}'.format(
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
    with attached_cli():
        stdout, stderr, return_code = run_dcos_command('security org service-accounts show --json')
        result_json = json.loads(stdout)
        return service_account in result_json


def delete_service_account(service_account):
    """Removes an existing service account. This method uses `dcos security org`
       command and assumes that `dcos-enterprise-cli` package is installed.

       :param service_account: service account name
       :type service_account: str
    """
    logger.info('Removing existing service account {}'.format(service_account))
    with attached_cli():
        cmd = 'security org service-accounts delete {}'.format(service_account)
        stdout, stderr, return_code = run_dcos_command(cmd)
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
    logger.info('Creating a key pair for the service account')
    with attached_cli():
        cmd = 'security org service-accounts keypair {} {}'.format(private_key_filename, public_key_filename)
        run_dcos_command(cmd)
        assert os.path.isfile(private_key_filename), "Private key of the service account key pair not found"
        assert os.path.isfile(public_key_filename), "Public key of the service account key pair not found"

        logger.info('Creating {} service account'.format(service_account))
        stdout, stderr, return_code = run_dcos_command('security org service-accounts create -p {} -d "{}" {}'.format(
                public_key_filename, account_description, service_account))

        os.remove(public_key_filename)
        assert return_code == 0


def set_service_account_permissions(service_account, resource='dcos:superuser', action='full'):
    """Set permissions for given `{service_account}` for passed `{resource}` with
       `{action}`. For more information consult the DC/OS documentation:
       https://docs.mesosphere.com/1.9/administration/id-and-access-mgt/permissions/user-service-perms/
    """
    try:
        logger.info('Granting {} permissions to {}/users/{}'.format(action, resource, service_account))
        url = dcos_url_path('acs/api/v1/acls/{}/users/{}/{}'.format(resource, service_account, action))
        auth = DCOSAcsAuth(dcos_acs_token())
        req = requests.put(url, auth=auth, verify=verify_ssl())
        req.raise_for_status()

        msg = 'Failed to grant permissions to the service account: {}, {}'.format(req, req.text)
        assert req.status_code == 204, msg
    except requests.HTTPError as e:
        if (e.response.status_code == 409):
            logger.info('Service account {} already has {} permissions set'.format(service_account, resource))
        else:
            logger.error("Unexpected HTTP error: {}".format(e.response))
            raise
    except Exception:
        logger.exception("Unexpected error when setting service account permissions")
        raise


def add_acs_resource(resource):
    """Create given ACS `{resource}`. For more information consult the DC/OS documentation:
       https://docs.mesosphere.com/1.9/administration/id-and-access-mgt/permissions/user-service-perms/
    """
    import json
    try:
        logger.info('Adding ACS resource: {}'.format(resource))
        url = dcos_url_path('acs/api/v1/acls/{}'.format(resource))
        auth = DCOSAcsAuth(dcos_acs_token())
        req = requests.put(url, data=json.dumps({'description': resource}),
                           headers={'Content-Type': 'application/json'}, auth=auth, verify=verify_ssl())
        req.raise_for_status()
        assert req.status_code == 201, 'Failed create ACS resource: {}, {}'.format(req, req.text)
    except requests.HTTPError as e:
        if (e.response.status_code == 409):
            logger.info('ACS resource {} already exists'.format(resource))
        else:
            logger.error("Unexpected HTTP error: {}, {}".format(e.response, e.response.text))
            raise
    except Exception:
        logger.exception("Unexpected error while adding ACS resource {}".format(resource))
        raise


def add_dcos_marathon_user_acls(user='root'):
    add_service_account_user_acls(service_account='dcos_marathon', user=user)


def add_service_account_user_acls(service_account, user='root'):
    resource = 'dcos:mesos:master:task:user:{}'.format(user)
    add_acs_resource(resource)
    set_service_account_permissions(service_account, resource, action='create')


def get_marathon_endpoint(path, marathon_name='marathon'):
    """Returns the url for the marathon endpoint."""
    return dcos_url_path('service/{}/{}'.format(marathon_name, path))


def http_get_marathon_path(name, marathon_name='marathon'):
    """Invokes HTTP GET for marathon url with name.
       For example, name='ping': http GET {dcos_url}/service/marathon/ping
    """
    url = get_marathon_endpoint(name, marathon_name)
    headers = {'Accept': '*/*'}
    auth = DCOSAcsAuth(dcos_acs_token())
    return requests.get(url, headers=headers, auth=auth, verify=verify_ssl())


# PR added to dcos-cli (however it takes weeks)
# https://github.com/dcos/dcos-cli/pull/974
def delete_marathon_path(name, marathon_name='marathon'):
    """Invokes HTTP DELETE for marathon url with name.
       For example, name='v2/leader': http DELETE {dcos_url}/service/marathon/v2/leader
    """
    url = get_marathon_endpoint(name, marathon_name)
    auth = DCOSAcsAuth(dcos_acs_token())
    return requests.delete(url, auth=auth, verify=verify_ssl())


@retrying.retry(wait_fixed=550, stop_max_attempt_number=60, retry_on_result=lambda a: a)
def wait_until_fail(endpoint):
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.delete(endpoint, auth=auth, verify=verify_ssl())
    return response.ok


def abdicate_marathon_leader(params="", marathon_name='marathon'):
    """
    Abdicates current leader. Waits until the HTTP service is stopped.

    params arg should include a "?" prefix.
    """
    leader_endpoint = get_marathon_endpoint('/v2/leader', marathon_name)
    auth = DCOSAcsAuth(dcos_acs_token())
    result = requests.delete(leader_endpoint + params, auth=auth, verify=verify_ssl())
    wait_until_fail(leader_endpoint)
    return result


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


def deployments_for(service_id=None, deployment_id=None, client=None):
    client = client or marathon.create_client()
    deployments = client.get_deployments()
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


def deployment_wait(service_id=None, deployment_id=None, wait_fixed=2000, max_attempts=60, client=None):
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

    assert_that(lambda: deployments_for(service_id, deployment_id, client),
                eventually(has_len(0), wait_fixed=wait_fixed, max_attempts=max_attempts))


@retrying.retry(wait_fixed=1000, stop_max_attempt_number=60, retry_on_exception=ignore_exception)
def __marathon_leadership_changed_in_mesosDNS(original_leader):
    """ This method uses mesosDNS to verify that the leadership changed.
        We have to retry because mesosDNS checks for changes only every 30s.
    """
    current_leader = marathon_leader_ip()
    logger.info(f'Current leader according to MesosDNS: {current_leader}, original leader: {original_leader}') # NOQA E999

    assert current_leader, "MesosDNS returned empty string for Marathon leader ip."
    error = f'Current leader did not change: original={original_leader}, current={current_leader}' # NOQA E999
    assert original_leader != current_leader, error
    return current_leader


@retrying.retry(wait_exponential_multiplier=1000, wait_exponential_max=30000, retry_on_exception=ignore_exception)
def __marathon_leadership_changed_in_marathon_api(original_leader):
    """ This method uses Marathon API to figure out that leadership changed.
        We have to retry here because leader election takes time and what might happen is that some nodes might
        not be aware of the new leader being elected resulting in HTTP 502.
    """
    # Leader is returned like this 10.0.6.88:8080 - we want just the IP
    current_leader = marathon.create_client().get_leader().split(':', 1)[0]
    logger.info('Current leader according to marathon API: {}'.format(current_leader))
    assert original_leader != current_leader
    return current_leader


def assert_marathon_leadership_changed(original_leader):
    """ Verifies leadership changed both by reading v2/leader as well as mesosDNS.
    """
    new_leader_marathon = __marathon_leadership_changed_in_marathon_api(original_leader)
    new_leader_dns = __marathon_leadership_changed_in_mesosDNS(original_leader)
    assert new_leader_marathon == new_leader_dns, "Different leader IPs returned by Marathon ({}) and MesosDNS ({})."\
        .format(new_leader_marathon, new_leader_dns)
    return new_leader_dns


def running_status_network_info(task_statuses):
    """ From a given list of statuses retrieved from mesos API it returns network info of running task.
    """
    return running_task_status(task_statuses)['container_status']['network_infos'][0]


def running_task_status(task_statuses):
    """ From a given list of statuses retrieved from mesos API it returns status of running task.
    """
    for task_status in task_statuses:
        if task_status['state'] == "TASK_RUNNING":
            return task_status

    assert False, "Did not find a TASK_RUNNING status in task statuses: %s" % (task_statuses,)


def task_by_name(tasks, name):
    """ Find mesos task by its name
    """
    for task in tasks:
        if task['name'] == name:
            return task

    assert False, "Did not find task with name %s in this list of tasks: %s" % (name, tasks,)


async def find_event(event_type, event_stream):
    async for event in event_stream:
        logger.info('Check event: {}'.format(event))
        if event['eventType'] == event_type:
            return event


async def assert_event(event_type, event_stream, within=10):
    await asyncio.wait_for(find_event(event_type, event_stream), within)


def kill_process_on_host(hostname, pattern):
    """ Kill the process matching pattern at ip

        :param hostname: the hostname or ip address of the host on which the process will be killed
        :param pattern: a regular expression matching the name of the process to kill
        :return: IDs of processes that got either killed or terminated on their own
    """

    cmd = "ps aux | grep -v grep | grep '{}' | awk '{{ print $2 }}' | tee >(xargs sudo kill -9)".format(pattern)
    status, stdout = run_command_on_agent(hostname, cmd)
    pids = [p.strip() for p in stdout.splitlines()]
    if pids:
        logger.info("Killed pids: {}".format(", ".join(pids)))
    else:
        logger.info("Killed no pids")
    return pids


@lru_cache()
def dcos_masters_public_ips():
    """
    retrieves public ips of all masters

    :return: public ips of all masters
    """
    @retrying.retry(
        wait_fixed=1000,
        stop_max_attempt_number=240,  # waiting 20 minutes for exhibitor start-up
        retry_on_exception=ignore_provided_exception(DCOSException))
    def all_master_ips():
        return get_all_master_ips()

    master_public_ips = [run_command(private_ip, '/opt/mesosphere/bin/detect_ip_public')[1]
                         for private_ip in all_master_ips()]

    return master_public_ips


def wait_for_service_endpoint(service_name, timeout_sec=120, path=""):
    """
    Checks the service url. Waits for exhibitor to start up (up to 20 minutes) and then checks the url on all masters.

    if available it returns true,
    on expiration throws an exception
    """

    def verify_ssl():
        cafile = get_ca_file()
        if cafile.is_file():
            return str(cafile)
        else:
            return False

    def master_service_status_code(url):
        logger.info('Querying %s', url)
        auth = DCOSAcsAuth(authentication.dcos_acs_token())

        response = requests.get(
            url=url,
            timeout=5,
            auth=auth,
            verify=verify_ssl())

        return response.status_code

    schema = 'https' if ee_version() == 'strict' or ee_version() == 'permissive' else 'http'
    logger.info('Waiting for service /service/{}/{} to become available on all masters'.format(service_name, path))

    for ip in dcos_masters_public_ips():
        url = "{}://{}/service/{}/{}".format(schema, ip, service_name, path)
        assert_that(lambda: master_service_status_code(url), eventually(equal_to(200), max_attempts=timeout_sec/5))
