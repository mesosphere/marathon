""" Test using root marathon.
    This test suite is used to test unreachable strategy configurations on live clusters.

    The first 2 tests test_unreachable_within_inactive_time and test_multi_node_failure_unreachable
    are used to provide reporting back.    Providing details on how long it takes for
    a task to have a replacement task and how long it takes to remove an extra task for a apps

    The 3rd test is an actual test to verify specific passing critia for the 24 hour test
    more detail at https://jira.mesosphere.com/browse/MARATHON_EE-1822
    This test requires a cluster with 3 private agents and the life of the cluster
    to be greater that 25 hours.
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
# TO RUN:  shakedown -q -m 87000 tests/system/test_unreachable.py::test_unreachable_within_inactive_time[360-360]
@pytest.mark.parametrize("inactive_sec, expunge_sec", [
  (0, 0),
  (60, 60),
  (360, 360),
  (60, 360),
  (60, 600),
  (600, 600),
  (1200, 1200)])
def test_unreachable_within_inactive_time(inactive_sec, expunge_sec):
    """ The goal of this test has changed over time.  It is now intended to provide
        a report of time for the different events of unreachable strat with different
        combinations of app_def configuration.   It is not intended to run all of these.
        A 20 min delay between is necessary between tests on the same cluster.
    """
    common.clean_up_marathon()
    app_def = apps.unreachable()
    app_id = app_def['id'].lstrip('/')

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
    print("Sleeping for {}".format(initial_unreachable_delay()))
    time.sleep(initial_unreachable_delay())
    # mesos unreachable event effected by 1) DCOS_AGENT_UNREACHABLE_TIME and 2) node rate limiting
    # this test ignores node rate limiting
    common.wait_for_unreachable_task()
    unreachable_time = time.time()
    print("Unreachable Time: {} seconds".format(elapse_time(start, unreachable_time)))
    print('Agent and Task Reported Unreachable.  Wait for inactiveAfterSeconds to engage.')

    # recovery based on inactive time (len(tasks) goes from 1 to 0 based on unreachable.  now we wait for 1.)
    # task recovery is dependent on inactive_sec and the reconcilation time window
    print("Sleeping for {}".format(inactive_sec*0.7))
    time.sleep(inactive_sec*0.7)
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
    print("Time to recover unreachable: {} seconds".format(elapse_time(inactive_time, time.time())))
    # now wait until the *new* task is no longer there
    common.wait_for_unreachable_task_kill(app_id=app_id, task_id=new_task_id, timeout_sec=expunge_sec+MARATHON_RECONCILATION_INTERNAL)
    expunge_time = time.time()
    print("Expunge Time from start: {} seconds".format(elapse_time(start, expunge_time)))
    print("Expunge Time from unreachable: {} seconds".format(elapse_time(unreachable_time, expunge_time)))
    tasks = client.get_tasks(app_id)
    task_remaining = tasks[0]['id']
    print("Remaining task id: {}".format(task_remaining))


def initial_unreachable_delay(inactive_time=0):
    """ Sleep the first 95% of the time.
        if 1 node, it is 95% of time (default is 5mins)  + time to react (inactive time)
        if > 1 node, it could be as long as 20m * number of nodes before anything happens but
        that can't be part of the initial wait
    """
    return 0.95 * DCOS_AGENT_UNREACHABLE_TIME + inactive_time


def elapse_time(start, end, precision=0):
    return round(end - start, precision)


def test_multi_node_failure_unreachable():
    """ The goal of this test is report the amount of time it takes
        DC/OS with the default configurations to provide Marathon a notification
        that a task is unreachable when multiple nodes fail prior to the
        node hosting the task fails.   Based on a 1/20m node rate limiter and testing
        a 5 private agent cluster, it will take <node_count>*20+<unhealth_timeout>
        or 5*20+5 = 105 mins or 1hr 45m.
    """
    common.clean_up_marathon()
    all_agents = shakedown.get_private_agents()
    num_of_agents = len(all_agents)

    app_def = apps.unreachable()
    app_id = app_def['id'].lstrip('/')
    inactive_sec = 0
    expunge_sec = 0

    app_def = common.unreachableStrategy(app_def, inactive=inactive_sec, expunge=expunge_sec)
    print(app_def)

    client = marathon.create_client()
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks(app_id)

    # confirm task and get task_id and host details.
    assert len(tasks) == 1, "The number of tasks is {} after deployment, but only 1 was expected".format(len(tasks))
    original_task_id = tasks[0]['id']
    host = tasks[0]['host']

    # task_agent_last -1
    task_agent_last = agent_list_host_last(all_agents, host)

    print('Starting Unreachable Test IP: {}'.format(host))
    print("Testing 'inactiveAfterSeconds':{},'expungeAfterSeconds':{}".format(inactive_sec, expunge_sec))
    print("task id: {}".format(original_task_id))

    start = time.time()

    # lose all non host agents
    # lose host agent (last)
    with common.agents_shutdown(task_agent_last):

        # mesos unreachable event effected by 1) DCOS_AGENT_UNREACHABLE_TIME and 2) node rate limiting
        # this test ignores node rate limiting
        # agents*20mins + 5min
        max_wait_time=num_of_agents*20*60*1000+5*1000
        common.wait_for_unreachable_task(timeout_sec=max_wait_time)

        unreachable_time = time.time()
        print("Unreachable Time: {} seconds".format(elapse_time(start, unreachable_time)))
        print('Agent and Task Reported Unreachable.  Wait for inactiveAfterSeconds to engage.')

        # recovery based on inactive time (len(tasks) goes from 1 to 0 based on unreachable.  now we wait for 1.)
        # task recovery is dependent on inactive_sec and the reconcilation time window
        print("Sleeping for {}".format(inactive_sec*0.7))
        time.sleep(inactive_sec*0.7)
        common.wait_for_marathon_task(app_id=app_id, task_id=original_task_id, timeout_sec=inactive_sec+MARATHON_RECONCILATION_INTERNAL*2)
        shakedown.deployment_wait()
        tasks = client.get_tasks(app_id)
        new_task_id = tasks[0]['id']
        inactive_time = time.time()
        print("new task id: {}".format(new_task_id))
        print("Inactive Time from start: {} seconds".format(elapse_time(start, inactive_time)))
        print("Inactive Time from unreachable: {} seconds".format(elapse_time(unreachable_time, inactive_time)))


    # first the unreachables go away (because they become reachable)
    # this will result in the app indicating 2 of 1 until the unreachable is killed
    common.wait_for_unreachable_task(inverse=True)
    print("Time to recover unreachable: {} seconds".format(elapse_time(inactive_time, time.time())))
    # now wait until the *new* task is no longer there
    common.wait_for_unreachable_task_kill(app_id=app_id, task_id=new_task_id, timeout_sec=expunge_sec+MARATHON_RECONCILATION_INTERNAL)
    expunge_time = time.time()
    print("Expunge Time from start: {} seconds".format(elapse_time(start, expunge_time)))
    print("Expunge Time from unreachable: {} seconds".format(elapse_time(unreachable_time, expunge_time)))
    tasks = client.get_tasks(app_id)
    task_remaining = tasks[0]['id']
    print("Remaining task id: {}".format(task_remaining))


def agent_list_host_last(all_agents, host):
    """ function does 2 things.  It reserves 1 agent (first non host agent)
        and reorder the reset of the agents such that the host agent is last.
        The reserve of 1 agent is so that a task can be recovered.
    """
    task_agent_last = []
    reserved_one = False
    for agent in all_agents:
        if agent != host and reserved_one:
            task_agent_last.append(agent)
        elif agent != host:
            print("Reserving {}".format(agent))
            reserved_one = True
    task_agent_last.append(host)

    return task_agent_last


# TO RUN:  shakedown -q -m 87000 tests/system/test_unreachable.py::test_24hour
def test_24hour():
    """
    Tests the inactive and expunge for 24 hours which is 86400 secs.
    # TO RUN:  shakedown -q -m 87000 tests/system/test_unreachable.py::test_24hour

    Test details:   Need 3 nodes (n1, n2, n3) which have an expiration of 25hours
    1. Start a task1 (t1) node it lands on is n1
    2. task2 to exclude n1 "constraints": [["hostname","UNLIKE","n1"] ], lands on n2
    3. stop n1 and n2
    4. sleep for 23 hrs
    5. check t1 and t2 are present and reported by marathon
    6. start n1 and wait for recovery
    7. check t1 id == original t1 id
    8. sleep 1 hr
    9. wait for replacement t2 (time = 2nodes * 20 mins + reconc window (10m))
    10. start n2
    11. wait for t2 to have 1 ID (time = reconc window(10m))
    """

    # setup and clean up
    common.clean_up_marathon()
    client = marathon.create_client()

    # cluster requirements
    all_agents = shakedown.get_private_agents()
    num_of_agents = len(all_agents)
    assert num_of_agents >= 3, "Unable to run test with less than 3 agents"

    # task 1 (recoverable)
    app_def = apps.unreachable()
    app_id1 = "recoverable"
    app_def['id'] = app_id1

    # 24 hours on task 1
    inactive_sec = 86400
    expunge_sec = 86400
    # inactive_sec = 1260
    # expunge_sec = 1260
    app_def = common.unreachableStrategy(app_def, inactive=inactive_sec, expunge=expunge_sec)
    print(app_def)
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks(app_id1)

    # confirm task and get task_id and host details.
    assert len(tasks) == 1, "The number of tasks is {} after deployment, but only 1 was expected".format(len(tasks))
    original_task1_id = tasks[0]['id']
    node1 = tasks[0]['host']

    # task 2 (unreachable)
    app_id2 = "unreachable"
    app_def['id'] = app_id2
    app_def['constraints'] = [["hostname","UNLIKE", node1]]  # don't land on the same node
    print(app_def)
    client.add_app(app_def)
    shakedown.deployment_wait()
    tasks = client.get_tasks(app_id2)

    # confirm task and get task_id and host details.
    assert len(tasks) == 1, "The number of tasks is {} after deployment, but only 1 was expected".format(len(tasks))
    original_task2_id = tasks[0]['id']
    node2 = tasks[0]['host']

    start = time.time()
    # # losing an agent
    shakedown.stop_agent(node1)
    time.sleep(10)   # test is flaky if agent down is at the same time.
    shakedown.stop_agent(node2)

    # sleep for 95% of 1st node unreachable reported time
    print("Sleeping for {} seconds".format(0.95 * DCOS_AGENT_UNREACHABLE_TIME))
    node1_initial_wait_time = 0.95 * DCOS_AGENT_UNREACHABLE_TIME
    time.sleep(node1_initial_wait_time)

    common.wait_for_unreachable_task(app_id=app_id1)
    node1_returned_time = time.time()
    print("App: '{}' reported unreachable by Mesos at time: {} seconds".format(app_id1, elapse_time(start, node1_returned_time)))

    node2_initial_wait_time = 0.95 * DCOS_NODE_RATE_LIMIT * 60
    print("Sleeping for {} seconds".format(node2_initial_wait_time))
    time.sleep(node2_initial_wait_time)
    common.wait_for_unreachable_task(app_id=app_id2)
    node2_returned_time = time.time()
    print("App: {} reported unreachable by Mesos at time: {} seconds".format(app_id2, elapse_time(start, node2_returned_time)))

    # # wait for 95% of total time (from here it is 95% of inactive)
    node2_sleep_time = max((0.95 * inactive_sec) - node2_initial_wait_time,0)
    print("Sleeping for {}".format(node2_sleep_time))
    time.sleep(node2_sleep_time)

    ### TEST 1  (recoverable) bring node back and make sure
    shakedown.start_agent(node1)
    # wait for there not to be an unreachable for this app_id
    common.wait_for_unreachable_task(app_id=app_id1, inverse=True)
    tasks = client.get_tasks(app_id1)

    # make sure there is only 1, make sure the task_id is the same
    assert len(tasks) == 1, "The number of tasks is {} after deployment, but only 1 was expected".format(len(tasks))
    assert original_task1_id == tasks[0]['id']

    ### TEST 2 (unreachable)
    # wait for second app to launch a new task within the time window
    # ** IMPORTANT**: the timeout is the test... it defines that a replacement
    # task landed within the replacement window (which is from [marathon notified + inactive_sec] to [+10mins])
    max_wait_task_time = inactive_sec - node2_sleep_time + (10 * 60)
    print("max time to wait for unreachable replacement task is {} seconds".format(max_wait_task_time))
    time.sleep(max_wait_task_time * 0.95)
    common.wait_for_marathon_task(app_id=app_id2, task_id=original_task2_id, timeout_sec=max_wait_task_time * 0.1)
    tasks = client.get_tasks(app_id2)
    new_task2_id = tasks[0]['id']
    assert len(tasks) == 1
    assert new_task2_id != original_task2_id

    ### TEST 3 (making the unreachable reachable)
    shakedown.start_agent(node2)

    # wait for there not to be an unreachable for this app_id
    common.wait_for_unreachable_task(app_id=app_id2, inverse=True)
    tasks = client.get_tasks(app_id2)

    # make sure there is only 1, make sure the task_id is the same
    # when expunge_sec is the same as inactive_sec this is flaky... the return could
    # be 1 or 2.  When expunge is greater... then we need to wait for the kill to occur
    if len(tasks) == 2:
        #### TEST 4 (back to 1 task within 10m)
        common.wait_for_unreachable_task_kill(app_id=app_id2, task_id=original_task2_id, timeout_sec=MARATHON_RECONCILATION_INTERNAL)
