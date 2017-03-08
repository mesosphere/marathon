"""Marathon acceptance tests for DC/OS.  This test suite specifically tests the root
   Marathon.
"""

import pytest
import retrying
import shakedown
import time
import uuid

from dcos import marathon
from common import (app_mesos, cluster_info, delete_all_apps_wait, event_fixture,
                    pending_deployment_due_to_cpu_requirement, pending_deployment_due_to_resource_roles,
                    stop_all_deployments)
from utils import fixture_dir, get_resource

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = shakedown.dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


def test_default_user():
    """ Ensures the default user of a task is started as root.  This is the default user.
    """

    # launch unique-sleep
    application_json = get_resource("{}/unique-sleep.json".format(fixture_dir()))
    client = marathon.create_client()
    client.add_app(application_json)
    app = client.get_app(application_json['id'])
    assert app['user'] is None

    # wait for deployment to finish
    tasks = client.get_tasks("unique-sleep")
    host = tasks[0]['host']

    assert shakedown.run_command_on_agent(host, "ps aux | grep '[s]leep ' | awk '{if ($1 !=\"root\") exit 1;}'")

    client = marathon.create_client()
    client.remove_app("/unique-sleep")


def test_launch_mesos_root_marathon_default_graceperiod():
    """  Test the 'taskKillGracePeriodSeconds' of a launched task from the root marathon.
         The graceperiod is the time after a kill sig to allow for a graceful shutdown.
         The default is 3 seconds.  The fetched test.py contains `signal.signal(signal.SIGTERM, signal.SIG_IGN)`.
    """
    app_def = app_mesos()
    app_def['id'] = 'grace'
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    # with marathon_on_marathon():
    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    # after waiting for deployment it exists
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # still present after a scale down.
    client.scale_app('/grace', 0)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # 3 sec is the default
    # task should be gone after 3 secs
    default_graceperiod = 3
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is None


def test_launch_mesos_root_marathon_graceperiod():
    """  Test the 'taskKillGracePeriodSeconds' of a launched task from the root marathon.
         The default is 3 seconds.  This tests setting that period to other than the default value.
    """
    app_def = app_mesos()
    app_def['id'] = 'grace'
    default_graceperiod = 3
    graceperiod = 20
    app_def['taskKillGracePeriodSeconds'] = graceperiod
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()

    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    client.scale_app('/grace', 0)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # task should still be here after the default_graceperiod
    time.sleep(default_graceperiod + 1)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is not None

    # but not after the set graceperiod
    time.sleep(graceperiod)
    tasks = shakedown.get_service_task('marathon', 'grace')
    assert tasks is None


def test_declined_offer_due_to_resource_role():
    """ Tests that an offer was declined because the role doesn't exist
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = pending_deployment_due_to_resource_roles(app_id)

    _test_declined_offer(app_id, app_def, 'UnfulfilledRole')


def test_declined_offer_due_to_cpu_requirements():
    """ Tests that an offer was declined because the number of cpus can't be found in an offer
    """
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = pending_deployment_due_to_cpu_requirement(app_id)

    _test_declined_offer(app_id, app_def, 'InsufficientCpus')


@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    """ Tests the event channel.  The way events are verified is by streaming the events
        to a test.txt file.   The fixture ensures the file is removed before and after the test.
        events checked are connecting, deploying a good task and killing a task.
    """
    app_def = app_mesos()
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

    client.remove_app(app_id, True)
    shakedown.deployment_wait()

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def check_kill_message():
        status, stdout = shakedown.run_command_on_master('cat test.txt')
        assert 'Killed' in stdout


def _test_declined_offer(app_id, app_def, reason):
    """ Used to confirm that offers were declined.   The `processedOffersSummary` and these tests
        in general require 1.4+ marathon with the queue end point.
        The retry is the best possible way to "time" the success of the test.
    """

    client = marathon.create_client()
    client.add_app(app_def)

    @retrying.retry(wait_fixed=1000, stop_max_delay=10000)
    def verify_declined_offer():
        deployments = client.get_deployments(app_id)
        assert len(deployments) == 1

        offer_summary = client.get_queued_app(app_id)['processedOffersSummary']
        role_summary = declined_offer_by_reason(offer_summary['rejectSummaryLastOffers'], reason)
        last_attempt = declined_offer_by_reason(offer_summary['rejectSummaryLaunchAttempt'], reason)

        assert role_summary['declined'] > 0
        assert role_summary['processed'] > 0
        assert last_attempt['declined'] > 0
        assert last_attempt['processed'] > 0


def setup_function(function):
    stop_all_deployments()
    delete_all_apps_wait()


def setup_module(module):
    cluster_info()


def declined_offer_by_reason(offers, reason):
    for offer in offers:
        if offer['reason'] == reason:
            del offer['reason']
            return offer

    return None


def teardown_module(module):
    stop_all_deployments()
    delete_all_apps_wait()
