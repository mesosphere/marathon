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

DCOS_AGENT_PING_TIMEOUT=15
DCOS_MAX_AGENT_PING_TIMEOUTS=20
# DCOS default is 15*20 = 300s == 5m
DCOS_AGENT_UNREACHABLE_TIME=DCOS_AGENT_PING_TIMEOUT * DCOS_MAX_AGENT_PING_TIMEOUTS

# node rate limit assume per 1 node in X mins.  The current default is 1/20 mins
# this is in case we want to automate the delay of each test.
DCOS_NODE_RATE_LIMIT=20

# marathon default reconcilation window is 600s == 10m
MARATHON_RECONCILATION_INTERNAL=600

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
    print("task id: {}".format(original_task_id))

    start = time.time()
    # losing an agent
    shakedown.stop_agent(original_host)
    time.sleep(initial_unreachable_delay())
    # mesos unreachable event effected by 1) DCOS_AGENT_UNREACHABLE_TIME and 2) node rate limiting
    # this test ignores node rate limiting
    common.wait_for_unreachable_task()
    unreachable_time = time.time()
    print("Unreachable Time: {} seconds".format(elapse_time(start, unreachable_time)))
    print('Agent and Task Reported Unreachable.  Wait for inactiveAfterSeconds to engage.')

    # recovery based on inactive time (len(tasks) goes from 1 to 0 based on unreachable.  now we wait for 1.)
    # task recovery is dependent on inactive_sec and the reconcilation time window
    common.wait_for_marathon_task(app_id=app_id, task_id=original_task_id, timeout_sec=inactive_sec+MARATHON_RECONCILATION_INTERNAL)
    shakedown.deployment_wait()
    tasks = client.get_tasks(app_id)
    new_task_id = tasks[0]['id']
    inactive_time = time.time()
    print("new task id: {}".format(new_task_id))
    print("Inactive Time from start: {} seconds".format(elapse_time(start, inactive_time)))
    print("Inactive Time from unreachable: {} seconds".format(elapse_time(unreachable_time, inactive_time)))

    # recovery
    shakedown.start_agent(original_host)

    # first the unreachables go away (because they become reachable)
    # this will result in the app indicating 2 of 1 until the unreachable is killed
    common.wait_for_unreachable_task(inverse=True)
    common.wait_for_unreachable_task_kill(app_id=app_id, task_id=original_task_id, timeout_sec=expunge_sec+MARATHON_RECONCILATION_INTERNAL)
    expunge_time = time.time()
    print("Expunge Time from start: {} seconds".format(elapse_time(start, expunge_time)))
    print("Expunge Time from unreachable: {} seconds".format(elapse_time(unreachable_time, expunge_time)))


def initial_unreachable_delay():
    """ Sleep the first 75% of the time.
    """
    return 0.75 * DCOS_AGENT_UNREACHABLE_TIME


def elapse_time(start, end):
    return round(end - start, 3)
