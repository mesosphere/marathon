from common import app, available_resources, get_cluster_metadata, ensure_mom_version
from datetime import timedelta
from dcos import marathon
import itertools
import logging
import math
import shakedown
from utils import marathon_on_marathon


def setup_module(module):
    """ Setup test module
    """
    logging.basicConfig(format='%(asctime)s %(levelname)-8s: %(message)s')


def setup_function(function):
    """ Setup test function
    """
    print(get_cluster_metadata())
    print(available_resources())


def app_def(app_id):
    return {
        "id": app_id,
        "instances":  1,
        "cmd": "for (( ; ; )); do sleep 100000000; done",
        "cpus": 0.01,
        "mem": 32,
        "disk": 0,
        "backoffFactor": 1.0,
        "backoffSeconds": 0,
    }


def linear_step_function(step_size=1000):
    """
    Curried linear step function that gives next instances size based on a
    step.
    """
    def inner(step):
        return step * step_size
    return inner


def exponential_decay(start=1000, decay=0.5):
    """
    Returns the next batch size which has a exponential decay.

    With default parameters we have:
    0:1000, 1:606.53, 2:367.88, 3:223.13, 4:135.34, 5:82.08

    Increase decay for a faster slow down of batches.

    This function is useful to jump to a certain size quickly and to slow down
    growth then.

    Always returns the lower integer with min 1.

    :start First batch size.
    :decay Exponential decay constant.
    """
    def inner(step):
        extact = start * math.exp(-1 * step * decay)
        approx = math.floor(extact)
        return max(1, approx)
    return inner


def incremental_steps(step_func):
    """
    Generator that yields new instances size in steps until eternity.

    :param step_func The current step number is passed to this function. It
        should return the next size. See 'linear_step_function' for an example.
    :yield Next size
    """
    for current_step in itertools.count(start=1):
        yield step_func(current_step)


def test_incremental_scale():
    """
    Scale instances of app in steps until the first error, e.g. a timeout, is
    reached.
    """

    client = marathon.create_client()
    client.add_app(app_def("cap-app"))

    for new_size in incremental_steps(linear_step_function(step_size=1000)):
        shakedown.echo("Scaling to {}".format(new_size))
        shakedown.deployment_wait(
            app_id='cap-app', timeout=timedelta(minutes=10).total_seconds())

        client.scale_app('/cap-app', new_size)
        shakedown.deployment_wait(
            app_id='cap-app', timeout=timedelta(minutes=10).total_seconds())
        shakedown.echo("done.")


def test_incremental_app_scale():
    """
    Scale number of app in steps until the first error, e.g. a timeout, is
    reached. The apps are created in root group.
    """

    client = marathon.create_client()
    client.remove_group('/')

    for step in itertools.count(start=1):
        shakedown.echo("Add new apps")

        app_id = "app-{0:0>4}".format(step)
        client.add_app(app_def(app_id))

        shakedown.deployment_wait(
                timeout=timedelta(minutes=15).total_seconds())

        shakedown.echo("done.")


def test_incremental_apps_per_group_scale():
    """
    Try to reach the maximum number of apps. We start with batches of apps in a
    group and decay the batch size.
    """

    client = marathon.create_client()

    batch_size_for = exponential_decay(start=500, decay=0.3)
    for step in itertools.count(start=0):
        batch_size = batch_size_for(step)
        shakedown.echo("Add {} apps".format(batch_size))

        group_id = "/batch-{0:0>3}".format(step)
        app_ids = ("app-{0:0>4}".format(i) for i in range(batch_size))
        app_definitions = [app_def(app_id) for app_id in app_ids]
        next_batch = {
            "apps": app_definitions,
            "dependencies": [],
            "id": group_id
        }

        client.create_group(next_batch)
        shakedown.deployment_wait(
                timeout=timedelta(minutes=15).total_seconds())

        shakedown.echo("done.")


def test_incremental_groups_scale():
    """
    Scale number of groups.
    """

    client = marathon.create_client()

    batch_size_for = exponential_decay(start=40, decay=0.01)
    total = 0
    for step in itertools.count(start=0):
        batch_size = batch_size_for(step)
        total += batch_size
        shakedown.echo("Add {} groups totaling {}".format(batch_size, total))

        group_ids = ("/group-{0:0>4}".format(step * batch_size + i)
                     for i in range(batch_size))
        app_ids = ("{}/app-1".format(g) for g in group_ids)
        app_definitions = [app_def(app_id) for app_id in app_ids]

        # There is no app id. We simply PUT /v2/apps to create groups in
        # batches.
        client.update_app('', app_definitions)
        shakedown.deployment_wait(
                timeout=timedelta(minutes=15).total_seconds())

        shakedown.echo("done.")


def test_incremental_group_nesting():
    """
    Scale depth of nested groups. Again we grow fast at the beginning and then
    slow the growth.
    """

    client = marathon.create_client()

    batch_size_for = exponential_decay(start=5, decay=0.1)
    depth = 0
    for step in itertools.count(start=0):
        batch_size = batch_size_for(step)
        depth += batch_size
        shakedown.echo("Create a group with a nesting of {}".format(depth))

        group_ids = ("group-{0:0>3}".format(g) for g in range(depth))
        nested_groups = '/'.join(group_ids)

        # Note: We always deploy into the same nested groups.
        app_id = '/{0}/app-1'.format(nested_groups)

        client.add_app(app_def(app_id))
        shakedown.deployment_wait(
                timeout=timedelta(minutes=15).total_seconds())

        shakedown.echo("done.")
