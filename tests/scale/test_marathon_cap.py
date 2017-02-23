from common import app, available_resources, cluster_info, ensure_mom_version
from datetime import timedelta
from dcos import marathon
import itertools
import shakedown
from utils import marathon_on_marathon

def linear_step_function(step_size=1000):
    """
    Curried linear step function that gives next instances size based on a step.
    """
    def inner(step):
        return step * step_size
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
    ensure_mom_version('1.4.0-RC7')

    cluster_info()
    print(available_resources())

    app_def = {
      "id": "cap-app",
      "instances":  1,
      "cmd": "for (( ; ; )); do sleep 100000000; done",
      "cpus": 0.001,
      "mem": 8,
      "disk": 0,
      "backoffFactor": 1.0,
      "backoffSeconds": 0,
    }

    with marathon_on_marathon():
        # shakedown.delete_app_wait('/cap-app')

        client = marathon.create_client()
        client.add_app(app_def)

        for new_size in incremental_steps(linear_step_function(step_size=1000)):
            shakedown.echo("Scaling to {}".format(new_size))
            shakedown.deployment_wait(
                app_id='cap-app', timeout=timedelta(minutes=10).total_seconds())

            # Scale to 200
            client.scale_app('/cap-app', new_size)
            shakedown.deployment_wait(
                app_id='cap-app', timeout=timedelta(minutes=10).total_seconds())
            shakedown.echo("done.")
