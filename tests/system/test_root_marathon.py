"""Marathon acceptance tests for DC/OS."""

import pytest
from dcos import marathon

from common import *
from shakedown import *
from utils import fixture_dir, get_resource

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300


@pytest.mark.sanity
def test_default_user():
    """Ensures the default user of a task that is created is started as root.
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

    assert run_command_on_agent(host, "ps aux | grep '[s]leep ' | awk '{if ($1 !=\"root\") exit 1;}'")

    client = marathon.create_client()
    client.remove_app("/unique-sleep")


def test_launch_mesos_root_marathon_default_graceperiod():
    delete_all_apps_wait()
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
    deployment_wait()

    tasks = get_service_task('marathon', 'grace')
    assert tasks is not None

    client.scale_app('/grace', 0)
    tasks = get_service_task('marathon', 'grace')
    assert tasks is not None

    # 3 sec is the default
    # should have task still
    time.sleep(5)
    tasks = get_service_task('marathon', 'grace')
    assert tasks is None


def test_launch_mesos_root_marathon_graceperiod():
    delete_all_apps_wait()
    app_def = app_mesos()
    app_def['id'] = 'grace'
    app_def['taskKillGracePeriodSeconds'] = 20
    fetch = [{
            "uri": "https://downloads.mesosphere.com/testing/test.py"
    }]
    app_def['fetch'] = fetch
    app_def['cmd'] = '/opt/mesosphere/bin/python test.py'

    client = marathon.create_client()
    client.add_app(app_def)
    deployment_wait()

    tasks = get_service_task('marathon', 'grace')
    assert tasks is not None

    client.scale_app('/grace', 0)
    tasks = get_service_task('marathon', 'grace')
    assert tasks is not None

    # 3 sec is the default
    # should have task still
    time.sleep(5)
    tasks = get_service_task('marathon', 'grace')
    assert tasks is not None
    time.sleep(20)
    tasks = get_service_task('marathon', 'grace')
    assert tasks is None


def test_declined_offer_due_to_resource_role():
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = pending_deployment_due_to_resource_roles(app_id)

    _test_declined_offer(app_id, app_def, 'UnfulfilledRole')


def test_declined_offer_due_to_cpu_requirements():
    app_id = '/{}'.format(uuid.uuid4().hex)
    app_def = pending_deployment_due_to_cpu_requirement(app_id)

    _test_declined_offer(app_id, app_def, 'InsufficientCpus')


@pytest.mark.usefixtures("event_fixture")
def test_event_channel():
    delete_all_apps_wait()
    app_def = app_mesos()
    app_id = app_def['id']

    client = marathon.create_client()
    client.add_app(app_def)
    deployment_wait()

    status, stdout = run_command_on_master('cat test.txt')

    assert 'event_stream_attached' in stdout
    assert 'deployment_info' in stdout
    assert 'deployment_step_success' in stdout

    client.remove_app(app_id)
    deployment_wait()
    status, stdout = run_command_on_master('cat test.txt')

    assert 'Killed' in stdout


def _test_declined_offer(app_id, app_def, reason):
    client = marathon.create_client()
    client.add_app(app_def)

    deployments = client.get_deployments(app_id)
    assert len(deployments) == 1

    # Is there a better way to wait for incomming offers?
    time.sleep(3)

    offer_summary = client.get_queued_app(app_id)['processedOffersSummary']
    role_summary = declined_offer_by_reason(offer_summary['rejectSummaryLastOffers'], reason)
    last_attempt = declined_offer_by_reason(offer_summary['rejectSummaryLaunchAttempt'], reason)

    assert role_summary['declined'] > 0
    assert role_summary['processed'] > 0
    assert last_attempt['declined'] > 0
    assert last_attempt['processed'] > 0


def declined_offer_by_reason(offers, reason):
    for offer in offers:
        if offer['reason'] == reason:
            del offer['reason']
            return offer

    return None


def teardown_module(module):
    stop_all_deployments()
    delete_all_apps_wait()
