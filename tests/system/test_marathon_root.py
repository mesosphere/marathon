""" Test using root marathon.
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to root marathon, specifically
    tests round dcos services registration and control and security.
"""
import common
import shakedown
import uuid

# this is intentional import *
# it imports all the common test_ methods which are to be tested on root and mom
from dcos_service_marathon_tests import *
from marathon_common_tests import *
from marathon_auth_common_tests import *
from marathon_pods_tests import *

from shakedown import (masters, required_masters, public_agents, required_public_agents,
                        dcos_1_9, marthon_version_less_than, marthon_version_less_than)

from datetime import timedelta

pytestmark = [pytest.mark.usefixtures('marathon_service_name')]


@pytest.fixture(scope="function")
def marathon_service_name():
    shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
    yield 'marathon'
    shakedown.wait_for_service_endpoint('marathon', timedelta(minutes=5).total_seconds())
    clear_marathon()


def setup_module(module):
    common.cluster_info()
    clear_marathon()


def teardown_module(module):
    clear_marathon()

##################
# Root specific tests
##################


@masters(3)
def test_marathon_delete_leader(marathon_service_name):

    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))
    common.delete_marathon_path('v2/leader')

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    @retrying.retry(stop_max_attempt_number=30)
    def marathon_leadership_changed():
        current_leader = shakedown.marathon_leader_ip()
        print('leader: {}'.format(current_leader))
        assert original_leader != current_leader

    marathon_leadership_changed()

@masters(3)
def test_marathon_delete_leader_and_check_apps(marathon_service_name):

    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))

    # start an app
    app_def = common.app(id=uuid.uuid4().hex)
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    app = client.get_app(app_id)
    assert app['tasksRunning'] == 1

    # abdicate leader after app was started successfully
    common.delete_marathon_path('v2/leader')

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    @retrying.retry(stop_max_attempt_number=30)
    def marathon_leadership_changed():
        current_leader = shakedown.marathon_leader_ip()
        print('leader: {}'.format(current_leader))
        assert original_leader != current_leader

    # wait until leader changed
    marathon_leadership_changed()

    @retrying.retry(stop_max_attempt_number=30)
    def check_app_existence(expected_instances):
        app = client.get_app(app_id)
        assert app['tasksRunning'] == expected_instances

    # check if app definition is still there and one instance is still running after new leader was elected
    check_app_existence(1)

    client.remove_app(app_id)
    shakedown.deployment_wait()

    app = client.get_app(app_id)
    assert app['tasksRunning'] == 0

    # abdicate leader after app was started successfully
    common.delete_marathon_path('v2/leader')

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    # wait until leader changed
    marathon_leadership_changed()

    # check if app definition is still not there and no instance is running after new leader was elected
    check_app_existence(0)


@masters(3)
def test_marathon_zk_partition_leader_change(marathon_service_name):

    original_leader = common.get_marathon_leader_not_on_master_leader_node()

    # blocking zk on marathon leader (not master leader)
    with shakedown.iptable_rules(original_leader):
        block_port(original_leader, 2181, direction='INPUT')
        block_port(original_leader, 2181, direction='OUTPUT')
        #  time of the zk block
        time.sleep(5)

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    current_leader = shakedown.marathon_leader_ip()
    assert original_leader != current_leader


@masters(3)
def test_marathon_master_partition_leader_change(marathon_service_name):

    original_leader = common.get_marathon_leader_not_on_master_leader_node()

    # blocking outbound connection to mesos master
    with shakedown.iptable_rules(original_leader):
        block_port(original_leader, 5050, direction='OUTPUT')
        #  time of the master block
        time.sleep(timedelta(minutes=1.5).total_seconds())

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    current_leader = shakedown.marathon_leader_ip()
    assert original_leader != current_leader


@public_agents(1)
def test_launch_app_on_public_agent():
    """ Test the successful launch of a mesos container on public agent.
        MoMs by default do not have slave_public access.
    """
    client = marathon.create_client()
    app_id = uuid.uuid4().hex
    app_def = common.add_role_constraint_to_app_def(app_mesos(app_id).copy(), ['slave_public'])
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    task_ip = tasks[0]['host']

    assert task_ip in shakedown.get_public_agents()


@pytest.mark.skipif("ee_version() == 'strict'")
@pytest.mark.skipif('marthon_version_less_than("1.3.9")')
@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    """ Tests the event channel.  The way events are verified is by streaming the events
        to a test.txt file.   The fixture ensures the file is removed before and after the test.
        events checked are connecting, deploying a good task and killing a task.
    """
    app_def = common.app_mesos()
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_deployment_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'event_stream_attached' in stdout
        assert 'deployment_info' in stdout
        assert 'deployment_step_success' in stdout

    check_deployment_message()
    client.remove_app(app_id, True)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_kill_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'KILLED' in stdout

    check_kill_message()


@pytest.mark.skipif("ee_version() == 'strict'")
@dcos_1_9
def test_external_volume():
    volume_name = "marathon-si-test-vol-{}".format(uuid.uuid4().hex)
    app_def = common.external_volume_mesos_app(volume_name)
    app_id = app_def['id']

    # Tested with root marathon since MoM doesn't have
    # --enable_features external_volumes option activated.
    # First deployment should create the volume since it has a unique name
    try:
        client = marathon.create_client()
        client.add_app(app_def)
        shakedown.deployment_wait()

        # Create the app: the volume should be successfully created
        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Scale down to 0
        client.stop_app(app_id)
        shakedown.deployment_wait()

        # Scale up again: the volume should be successfully reused
        client.scale_app(app_id, 1)
        shakedown.deployment_wait()

        common.assert_app_tasks_running(client, app_def)
        common.assert_app_tasks_healthy(client, app_def)

        # Remove the app to be able to remove the volume
        client.remove_app(app_id)
        shakedown.deployment_wait()
    except Exception as e:
        print('Fail to test external volumes: {}'.format(e))
        raise e
    finally:
        # Clean up after the test: external volumes are not destroyed by marathon or dcos
        # and have to be cleaned manually.
        agent = shakedown.get_private_agents()[0]
        status, output = shakedown.run_command_on_agent(agent, 'sudo /opt/mesosphere/bin/dvdcli remove --volumedriver=rexray --volumename={}'.format(volume_name))  # NOQA
        # Note: Removing the volume might fail sometimes because EC2 takes some time (~10min) to recognize that
        # the volume is not in use anymore hence preventing it's removal. This is a known pitfall: we log the error
        # and the volume should be cleaned up manually later.
        if not status:
            print('WARNING: Failed to remove external volume with name={}: {}'.format(volume_name, output))


# Backup and restore meeting is done with only one master since new master has to be able
# to read the backup file that was created by the previous master and the easiest way to
# test it is when there is 1 master
@pytest.mark.skipif('common.multi_master() or marthon_version_less_than("1.5")')
def test_marathon_backup_and_restore_leader(marathon_service_name):

    backup_file = 'backup.tar'
    backup_dir = '/tmp'
    backup_url = 'file://{}/{}'.format(backup_dir, backup_file)

    # Deploy a simple test app. It is expected to be there after leader reelection
    client = marathon.create_client()
    app_def = {
        "id": "/sleep",
        "instances": 1,
        "cpus": 0.01,
        "mem": 32,
        "cmd": "sleep 100000"
    }

    app_id = app_def['id']
    client.add_app(app_def)
    shakedown.deployment_wait()

    app = client.get_app(app_id)
    assert app['tasksRunning'] == 1
    task_id = app['tasks'][0]['id']

    # Abdicate the leader with backup and restore
    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))
    url = 'v2/leader?backup={}&restore={}'.format(backup_url, backup_url)
    print('DELETE {}'.format(url))
    common.delete_marathon_path(url)

    # Wait for new leader (but same master server) to be up and ready
    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())
    app = client.get_app(app_id)
    assert app['tasksRunning'] == 1
    assert task_id == app['tasks'][0]['id'], "Task has a different Id after restore"

    # Check if the backup file exits and is valid
    cmd = 'tar -tf {}/{} | wc -l'.format(backup_dir, backup_file)
    status, data = shakedown.run_command_on_master(cmd)
    assert status, 'Failed to validate backup file {}'.format(backup_url)
    assert int(data.rstrip()) > 0, "Backup file is empty"


# Regression for MARATHON-7525, introduced in MARATHON-7538
@masters(3)
@pytest.mark.skipif('marthon_version_less_than("1.5")')
def test_marathon_backup_and_check_apps(marathon_service_name):

    backup_file1 = 'backup1.tar'
    backup_file2 = 'backup2.tar'
    backup_dir = '/tmp'
    backup_url1 = 'file://{}/{}'.format(backup_dir, backup_file1)
    backup_url2 = 'file://{}/{}'.format(backup_dir, backup_file2)

    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))

    # start an app
    app_def = common.app(id=uuid.uuid4().hex)
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    app = client.get_app(app_id)
    assert app['tasksRunning'] == 1

    # Abdicate the leader with backup
    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))
    url = 'v2/leader?backup={}'.format(backup_url1)
    print('DELETE {}'.format(url))
    common.delete_marathon_path(url)

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    @retrying.retry(stop_max_attempt_number=30)
    def marathon_leadership_changed():
        current_leader = shakedown.marathon_leader_ip()
        print('leader: {}'.format(current_leader))
        assert original_leader != current_leader

    # wait until leader changed
    marathon_leadership_changed()

    @retrying.retry(stop_max_attempt_number=30)
    def check_app_existence(expected_instances):
        app = client.get_app(app_id)
        assert app['tasksRunning'] == expected_instances

    # check if app definition is still there and one instance is still running after new leader was elected
    check_app_existence(1)

    # then remove
    client.remove_app(app_id)
    shakedown.deployment_wait()

    app = client.get_app(app_id)
    assert app['tasksRunning'] == 0

    # Do a second backup. Before MARATHON-7525 we had the problem, that doing a backup after an app was deleted
    # leads to the state that marathon was not able to re-start, because the second backup failed constantly.

    # Abdicate the leader with backup
    original_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(original_leader))
    url = 'v2/leader?backup={}'.format(backup_url2)
    print('DELETE {}'.format(url))
    common.delete_marathon_path(url)

    shakedown.wait_for_service_endpoint(marathon_service_name, timedelta(minutes=5).total_seconds())

    # wait until leader changed
    # if leader changed, this means that marathon was able to start again, which is great :-).
    marathon_leadership_changed()

    # check if app definition is still not there and no instance is running after new leader was elected
    check_app_existence(0)


@pytest.mark.skipif('marthon_version_less_than("1.5")')
def test_app_file_based_secret(secret_fixture):
    # Install enterprise-cli since it's needed to create secrets
    # if not common.is_enterprise_cli_package_installed():
    # common.install_enterprise_cli_package()

    secret_name, secret_value = secret_fixture
    secret_normalized_name = secret_name.replace('/', '')
    secret_container_path = 'mysecretpath'

    app_id = uuid.uuid4().hex
    # In case you're wondering about the `cmd`: secrets are mounted via tmpfs inside
    # the container and are not visible outside, hence the intermediate file
    app_def = {
        "id": app_id,
        "instances": 1,
        "cpus": 0.1,
        "mem": 64,
        "cmd": "cat {} >> {}_file && /opt/mesosphere/bin/python -m http.server $PORT_API".
            format(secret_container_path, secret_container_path),
        "container": {
            "type": "MESOS",
            "volumes": [{
                "containerPath": secret_container_path,
                "secret": "secret1"
            }]
        },
        "portDefinitions": [{
            "port": 0,
            "protocol": "tcp",
            "name": "api",
            "labels": {}
        }],
        "secrets": {
            "secret1": {
                "source": secret_name
            }
        }
    }

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    assert len(tasks) == 1, 'Failed to start the file based secret app'

    port = tasks[0]['ports'][0]
    host = tasks[0]['host']
    # The secret by default is saved in $MESOS_SANDBOX/.secrets/path/to/secret
    cmd = "curl {}:{}/{}_file".format(host, port, secret_container_path)
    status, data = shakedown.run_command_on_master(cmd)

    assert status, "{} did not succeed".format(cmd)
    assert data == secret_value


@dcos_1_9
def test_app_secret_env_var(secret_fixture):

    secret_name, secret_value = secret_fixture

    app_id = uuid.uuid4().hex
    app_def = {
        "id": app_id,
        "instances": 1,
        "cpus": 0.1,
        "mem": 64,
        "cmd": "echo $SECRET_ENV >> $MESOS_SANDBOX/secret-env && /opt/mesosphere/bin/python -m http.server $PORT_API",
        "env": {
            "SECRET_ENV": {
                "secret": "secret1"
            }
        },
        "portDefinitions": [{
            "port": 0,
            "protocol": "tcp",
            "name": "api",
            "labels": {}
        }],
        "secrets": {
            "secret1": {
                "source": secret_name
            }
        }
    }

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = client.get_tasks(app_id)
    assert len(tasks) == 1, 'Failed to start the secret environment variable app'

    port = tasks[0]['ports'][0]
    host = tasks[0]['host']
    cmd = "curl {}:{}/secret-env".format(host, port)
    status, data = shakedown.run_command_on_master(cmd)

    assert status, "{} did not succeed".format(cmd)
    assert data.rstrip() == secret_value


@dcos_1_9
def test_pod_secret_env_var(secret_fixture):
    # Install enterprise-cli since it's needed to create secrets
    if not common.is_enterprise_cli_package_installed():
        common.install_enterprise_cli_package()

    secret_name, secret_value = secret_fixture

    pod_id = '/{}'.format(uuid.uuid4().hex)
    pod_def = {
        "id": pod_id,
        "containers": [{
            "name": "container-1",
            "resources": {
                "cpus": 0.1,
                "mem": 64
            },
            "endpoints": [{
                "name": "http",
                "hostPort": 0,
                "protocol": [
                    "tcp"
                ]}
            ],
            "exec": {
                "command": {
                    "shell": "echo $SECRET_ENV && echo $SECRET_ENV >> $MESOS_SANDBOX/secret-env && /opt/mesosphere/bin/python -m http.server $ENDPOINT_HTTP"
                }
            }
        }],
        "environment": {
            "SECRET_ENV": {
                "secret": "secret1"
            }
        },
        "networks": [{
            "mode": "host"
        }],
        "secrets": {
            "secret1": {
                "source": secret_name
            }
        }
    }

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    instances = client.show_pod(pod_id)['instances']
    assert len(instances) == 1, 'Failed to start the secret environment variable pod'

    port = instances[0]['containers'][0]['endpoints'][0]['allocatedHostPort']
    host = instances[0]['networks'][0]['addresses'][0]
    cmd = "curl {}:{}/secret-env".format(host, port)
    status, data = shakedown.run_command_on_master(cmd)

    assert status, "{} did not succeed".format(cmd)
    assert data.rstrip() == secret_value


@pytest.mark.skipif('marthon_version_less_than("1.5")')
def test_pod_file_based_secret(secret_fixture):
    # Install enterprise-cli since it's needed to create secrets
    if not common.is_enterprise_cli_package_installed():
        common.install_enterprise_cli_package()

    secret_name, secret_value = secret_fixture
    secret_normalized_name = secret_name.replace('/', '')

    pod_id = '/{}'.format(uuid.uuid4().hex)

    pod_def = {
        "id": pod_id,
        "containers": [{
            "name": "container-1",
            "resources": {
                "cpus": 0.1,
                "mem": 64
            },
            "endpoints": [{
                "name": "http",
                "hostPort": 0,
                "protocol": [
                    "tcp"
                ]}
            ],
            "exec": {
                "command": {
                    "shell": "cat {} >> {}_file && /opt/mesosphere/bin/python -m http.server $ENDPOINT_HTTP".format(secret_normalized_name, secret_normalized_name),
                }
            },
            "volumeMounts": [{
                "name": "vol",
                "mountPath": secret_name
            }],
        }],
        "networks": [{
            "mode": "host"
        }],
        "volumes": [{
            "name": "vol",
            "secret": "secret1"
        }],
        "secrets": {
            "secret1": {
                "source": secret_name
            }
        }
    }

    client = marathon.create_client()
    client.add_pod(pod_def)
    shakedown.deployment_wait()

    instances = client.show_pod(pod_id)['instances']
    assert len(instances) == 1, 'Failed to start the file based secret pod'

    port = instances[0]['containers'][0]['endpoints'][0]['allocatedHostPort']
    host = instances[0]['networks'][0]['addresses'][0]
    cmd = "curl {}:{}/{}_file".format(host, port, secret_normalized_name)
    status, data = shakedown.run_command_on_master(cmd)

    assert status, "{} did not succeed".format(cmd)
    assert data.rstrip() == secret_value


@pytest.fixture(scope="function")
def secret_fixture():
    # Install enterprise-cli since it's needed to create secrets
    if not common.is_enterprise_cli_package_installed():
        common.install_enterprise_cli_package()

    secret_name = '/mysecret'
    secret_value = 'super_secret_password'
    common.create_secret(secret_name, secret_value)
    yield secret_name, secret_value
    common.delete_secret(secret_name)
