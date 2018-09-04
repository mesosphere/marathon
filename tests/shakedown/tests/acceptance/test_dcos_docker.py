import logging

from shakedown import *

logger = logging.getLogger(__name__)

def test_docker_version():
    master_docker_version = docker_version()
    logger.info("DC/OS master is running Docker Server {}".format(master_docker_version))
    assert master_docker_version != 'unknown'
