"""Marathon acceptance tests for DC/OS."""

import pytest
from dcos import marathon

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


def teardown_module(module):
    client = marathon.create_client()
    client.remove_app("/unique-sleep")
