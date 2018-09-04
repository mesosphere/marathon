from shakedown import *

def test_docker_version():
    master_docker_version = docker_version()
    print("DC/OS master is running Docker Server {}".format(master_docker_version))
    assert master_docker_version != 'unknown'
