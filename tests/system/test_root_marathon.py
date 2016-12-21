"""Marathon acceptance tests for DC/OS."""

import pytest
from dcos import marathon

from common import app_mesos
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


def teardown_module(module):
    delete_all_apps_wait()
