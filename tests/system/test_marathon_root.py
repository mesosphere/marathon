""" Test using root marathon.
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to root marathon, specifically
    tests round dcos services registration and control and security.
"""
import common
import shakedown

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
        result, output = shakedown.run_command_on_agent(agent, 'sudo /opt/mesosphere/bin/dvdcli remove --volumedriver=rexray --volumename={}'.format(volume_name))  # NOQA
        # Note: Removing the volume might fail sometimes because EC2 takes some time (~10min) to recognize that
        # the volume is not in use anymore hence preventing it's removal. This is a known pitfall: we log the error
        # and the volume should be cleaned up manually later.
        if not result:
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
    run, data = shakedown.run_command_on_master(cmd)
    assert run, 'Failed to validate backup file {}'.format(backup_url)
    assert int(data.rstrip()) > 0, "Backup file is empty"
