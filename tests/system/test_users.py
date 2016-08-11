"""Marathon acceptance tests for DC/OS."""

from shakedown import *

import pytest
import os
from utils import fixture_dir, get_resource

from dcos import marathon

PACKAGE_NAME = 'marathon'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_NAME)
WAIT_TIME_IN_SECS = 300

@pytest.mark.sanity
def test_default_user():
    """Install the Marathon package for DC/OS.
    """

    # launch unique-sleep
    application_json = get_resource("{}/unique-sleep.json".format(fixture_dir()))
    client = marathon.create_client()
    client.add_app(application_json)
    app = client.get_app(application_json['id'])
    assert app['user'] == None

    # wait for deployment
    tasks = client.get_tasks("unique-sleep")
    host = tasks[0]['host']

    # need a solution from shakedown "run_command_on_agent" is broken IMO
    # response = run_command_on_agent(host,"ps aux | grep '[s]leep 42000' | awk '{print $1}'")
    # It doesn't return the output... PR on shakedown coming
    assert run_command_on_agent(host,"ps aux | grep '[s]leep ' | awk '{if ($1 != 'root') exit 1;}'")


def teardown_module(module):
    client = marathon.create_client()
    client.remove_app("/unique-sleep")
