""" Test using marathon on marathon (MoM).
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to MoM environments only.
"""

import apps
import common
import pytest
import retrying
import scripts
import shakedown
import time
import logging

from datetime import timedelta
from shakedown.clients import mesos
from shakedown.dcos.agent import restart_agent
from shakedown.dcos.command import run_command_on_master
from shakedown.dcos.marathon import deployment_wait, marathon_on_marathon
from shakedown.dcos.package import uninstall_package_and_wait
from shakedown.dcos.task import wait_for_task
from shakedown.dcos.zookeeper import delete_zk_node

# the following lines essentially do:
#     from marathon_common_tests import test_*
import marathon_common_tests
for attribute in dir(marathon_common_tests):
    if attribute.startswith('test_'):
        exec("from marathon_common_tests import {}".format(attribute))

from shakedown.dcos.agent import required_private_agents # NOQA
from fixtures import wait_for_marathon_user_and_cleanup # NOQA


logger = logging.getLogger(__name__)

pytestmark = [pytest.mark.usefixtures('wait_for_marathon_user_and_cleanup')]


@pytest.fixture(scope="function")
def marathon_service_name():
    return "marathon-user"


def setup_module(module):
    common.ensure_mom()
    common.cluster_info()
    with marathon_on_marathon() as client:
        common.clean_up_marathon(client=client)


def teardown_module(module):
    with marathon_on_marathon() as client:
        try:
            common.clean_up_marathon(client=client)
        except Exception:
            pass

    uninstall_package_and_wait('marathon')
    delete_zk_node('universe/marathon-user')

    # Remove everything from root marathon
    common.clean_up_marathon()


#################################################
# MoM only tests
#################################################


def test_ui_registration_requirement():
    """ Testing the UI is a challenge with this toolchain.  The UI team has the
        best tooling for testing it.  This test verifies that the required configurations
        for the service endpoint and ability to launch to the service UI are present.
    """
    tasks = mesos.get_master().tasks()
    for task in tasks:
        if task['name'] == 'marathon-user':
            for label in task['labels']:
                if label['key'] == 'DCOS_PACKAGE_NAME':
                    assert label['value'] == 'marathon'
                if label['key'] == 'DCOS_SERVICE_NAME':
                    assert label['value'] == 'marathon-user'


@shakedown.dcos.agent.private_agents(2)
def test_mom_when_mom_agent_bounced():
    """Launch an app from MoM and restart the node MoM is on."""

    app_def = apps.sleep_app()
    app_id = app_def["id"]
    mom_ip = common.ip_of_mom()
    host = common.ip_other_than_mom()
    common.pin_to_host(app_def, host)

    with marathon_on_marathon() as client:
        client.add_app(app_def)
        deployment_wait(service_id=app_id, client=client)
        tasks = client.get_tasks(app_id)
        original_task_id = tasks[0]['id']

        restart_agent(mom_ip)

        @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
        def check_task_is_back():
            tasks = client.get_tasks(app_id)
            assert tasks[0]['id'] == original_task_id, "The task ID has changed"

        check_task_is_back()


@shakedown.dcos.agent.private_agents(2)
def test_mom_when_mom_process_killed():
    """Launched a task from MoM then killed MoM."""

    app_def = apps.sleep_app()
    app_id = app_def["id"]
    host = common.ip_other_than_mom()
    common.pin_to_host(app_def, host)

    with marathon_on_marathon() as client:
        client.add_app(app_def)
        deployment_wait(service_id=app_id, client=client)
        tasks = client.get_tasks(app_id)
        original_task_id = tasks[0]['id']

        common.kill_process_on_host(common.ip_of_mom(), 'marathon-assembly')
        wait_for_task('marathon', 'marathon-user', 300)
        common.wait_for_service_endpoint('marathon-user', path="ping")

        @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
        def check_task_is_back():
            tasks = client.get_tasks(app_id)
            assert tasks[0]['id'] == original_task_id, "The task ID has changed"

        check_task_is_back()


@shakedown.dcos.agent.private_agents(2)
def test_mom_with_network_failure():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by knocking out ports."""

    mom_ip = common.ip_of_mom()
    logger.info("MoM IP: {}".format(mom_ip))

    app_def = apps.sleep_app()
    app_id = app_def["id"]

    with marathon_on_marathon() as client:
        client.add_app(app_def)
        wait_for_task("marathon-user", app_id.lstrip('/'))
        tasks = client.get_tasks(app_id)
        original_task_id = tasks[0]["id"]
        task_ip = tasks[0]['host']

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    time.sleep(timedelta(minutes=1).total_seconds())

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    time.sleep(timedelta(minutes=1).total_seconds())
    common.wait_for_service_endpoint('marathon-user', timedelta(minutes=5).total_seconds(), path="ping")
    wait_for_task("marathon-user", app_id.lstrip('/'))

    with marathon_on_marathon() as client:
        wait_for_task("marathon-user", app_id.lstrip('/'))

        @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
        def check_task_is_back():
            tasks = client.get_tasks(app_id)
            assert tasks[0]['id'] == original_task_id, "The task ID has changed"

        check_task_is_back()


@shakedown.dcos.cluster.dcos_1_9
@shakedown.dcos.agent.private_agents(2)
def test_mom_with_network_failure_bounce_master():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by knocking out ports."""

    # get MoM ip
    mom_ip = common.ip_of_mom()
    logger.info("MoM IP: {}".format(mom_ip))

    app_def = apps.sleep_app()
    app_id = app_def["id"]

    with marathon_on_marathon() as client:
        client.add_app(app_def)
        wait_for_task("marathon-user", app_id.lstrip('/'))
        tasks = client.get_tasks(app_id)
        original_task_id = tasks[0]["id"]
        task_ip = tasks[0]['host']
        logger.info("\nTask IP: " + task_ip)

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    time.sleep(timedelta(minutes=1).total_seconds())

    # bounce master
    run_command_on_master("sudo systemctl restart dcos-mesos-master")

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    time.sleep(timedelta(minutes=1).total_seconds())
    common.wait_for_service_endpoint('marathon-user', timedelta(minutes=10).total_seconds(), path="ping")

    with marathon_on_marathon() as client:
        wait_for_task("marathon-user", app_id.lstrip('/'), timedelta(minutes=10).total_seconds())

        @retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
        def check_task_is_back():
            tasks = client.get_tasks(app_id)
            assert tasks[0]['id'] == original_task_id, "The task ID has changed"

        check_task_is_back()


def test_framework_unavailable_on_mom():
    """Launches an app that has elements necessary to create a service endpoint in DCOS.
       This test confirms that the endpoint is not created when launched with MoM.
    """

    app_def = apps.fake_framework()
    app_id = app_def["id"]

    with marathon_on_marathon() as client:
        client.add_app(app_def)
        deployment_wait(service_id=app_id, client=client)
    try:
        common.wait_for_service_endpoint('pyfw', 15)
    except Exception:
        pass
    else:
        assert False, 'MoM shoud NOT create a service endpoint'


def partition_agent(hostname):
    """Partition a node from all network traffic except for SSH and loopback"""

    shakedown.dcos.file.copy_file_to_agent(hostname, "{}/net-services-agent.sh".format(scripts.scripts_dir()))
    logger.info("partitioning {}".format(hostname))
    shakedown.dcos.command.run_command_on_agent(hostname, 'sh net-services-agent.sh fail')


def reconnect_agent(hostname):
    """Reconnect a node to cluster"""

    shakedown.dcos.command.run_command_on_agent(hostname, 'sh net-services-agent.sh')
