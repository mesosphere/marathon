""" Test using root marathon.
    This test suite is used to test unreachable strategy configurations on live clusters.
"""

import apps
import common
import json
import os
import pytest
import retrying
import shakedown
import time
import uuid

from dcos.mesos import DCOSClient
from dcos import marathon, errors, mesos
from datetime import timedelta


from shakedown import dcos_version_less_than, marthon_version_less_than
from fixtures import wait_for_marathon_and_cleanup


@pytest.fixture(scope="function")
def marathon_service_name():
    return "marathon"


def setup_module(module):
    common.cluster_info()


def teardown_module(module):
    common.clean_up_marathon()

# This can not be run currently as `shakedown -q tests/system/test_unreachable.py`
# They currently need a 20 min delay prior to running each one separately or you get
# inappropriate results.
# TO RUN:  shakedown -q tests/system/test_unreachable.py::test_unreachable_within_inactive_time[360-360]
@pytest.mark.parametrize("inactive_sec, expunge_sec", [
  (0, 0),
  (60, 60),
  (360, 360),
  (60, 360),
  (600, 600),
  (1200, 1200)])
def test_unreachable_within_inactive_time(inactive_sec, expunge_sec):

    common.clean_up_marathon()
    app_def = apps.unreachable()
    app_id = app_def['id']

    app_def = common.unreachableStrategy(app_def, inactive=inactive_sec, expunge=expunge_sec)
    print(app_def)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks(app_id)

    # confirm task and get task_id and host details.
    assert len(tasks) == 1, "The number of tasks is {} after deployment, but only 1 was expected".format(len(tasks))
    original_task_id = tasks[0]['id']
    original_host = tasks[0]['host']
    print('Starting Unreachable Test IP: {}'.format(original_host))
    print("Testing 'inactiveAfterSeconds':{},'expungeAfterSeconds':{}".format(inactive_sec, expunge_sec))
    start = time.time()
    # losing an agent
    shakedown.stop_agent(original_host)
    time.sleep(240)
    common.wait_for_unreachable_task()
    unreachable_time = time.time()
    print("Unreachable Time: {} seconds".format(round(unreachable_time - start, 3)))
    print('Agent and Task Reported Unreachable.  Wait for inactiveAfterSeconds to engage.')

    # recovery based on inactive time (len(tasks) goes from 1 to 0 based on unreachable.  now we wait for 1.)
    common.wait_for_marathon_task('marathon', app_id=app_id, timeout_sec=inactive_sec+300)
    inactive_time = time.time()
    print("Inactive Time from start: {} seconds".format(round(inactive_time - start, 3)))
    print("Inactive Time from unreachable: {} seconds".format(round(inactive_time - unreachable_time, 3)))

    # recovery
    shakedown.start_agent(original_host)

    # first we lose unreachable (because it becomes reachable)
    common.wait_for_unreachable_task(inverse=True)
    # next we wait for the number of tasks. (len(tasks) goes from 1 to 2 based on reachability.  now we wait for 1.)
    common.wait_for_marathon_task('marathon',  app_id=app_id, timeout_sec=inactive_sec+300)
    expunge_time = time.time()
    print("Expunge Time from start: {} seconds".format(round(expunge_time - start, 3)))
    print("Expunge Time from unreachable: {} seconds".format(round(expunge_time - unreachable_time, 3)))
