""" Test using marathon on marathon (MoM).
    This test suite imports all common tests found in marathon_common.py which are
    to be tested on root marathon and MoM.
    In addition it contains tests which are specific to MoM environments only.
"""

import apps
import common
import os
import pytest
import retrying
import scripts
import shakedown
import time

from datetime import timedelta
from dcos import mesos
from shakedown import marathon

# the following lines essentially do:
#     from marathon_common_tests import test_*
import marathon_common_tests
for attribute in dir(marathon_common_tests):
    if attribute.startswith('test_'):
        exec("from marathon_common_tests import {}".format(attribute))

from shakedown import dcos_version_less_than, required_private_agents
from fixtures import wait_for_marathon_user_and_cleanup


pytestmark = [pytest.mark.usefixtures('wait_for_marathon_user_and_cleanup')]


@pytest.fixture(scope="function")
def marathon_service_name():
    return "marathon-user"


def setup_module(module):
    mom_version = "v1.4.5"
    try:
        mom_version = os.environ['DOCKER_MOM_VERSION']
    except:
        pass

    common.install_docker_mom(mom_version)

    shakedown.wait_for_service_endpoint('marathon-user')
    common.cluster_info()
    with shakedown.marathon_on_marathon():
        common.clean_up_marathon()



def teardown_module(module):
    with shakedown.marathon_on_marathon():
        try:
            common.clean_up_marathon()
        except:
            pass

    # remove docker mom
    client = marathon.create_client()
    client.remove_group("/")
    shakedown.deployment_wait()
    shakedown.delete_zk_node('universe/marathon-user')
