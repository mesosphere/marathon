"""Utilities for working with master"""
import contextlib
import logging
import json
import pytest
import requests

from datetime import timedelta
from precisely import equal_to

from . import master_ip, master_url, network
from .agent import kill_process_from_pid_file_on_host
from .command import run_command_on_master
from .zookeeper import get_zk_node_children, get_zk_node_data
from ..clients.authentication import dcos_acs_token, DCOSAcsAuth
from ..clients.rpcclient import verify_ssl
from ..matcher import assert_that, eventually

DISABLE_MASTER_INCOMING = "-I INPUT -p tcp --dport 5050 -j REJECT"
DISABLE_MASTER_OUTGOING = "-I OUTPUT -p tcp --sport 5050 -j REJECT"

logger = logging.getLogger(__name__)


def partition_master(incoming=True, outgoing=True):
    """ Partition master's port alone. To keep DC/OS cluster running.

    :param incoming: Partition incoming traffic to master process. Default True.
    :param outgoing: Partition outgoing traffic from master process. Default True.
    """

    logger.info('Partitioning master. Incoming:%s | Outgoing:%s', incoming, outgoing)

    network.save_iptables(master_ip())
    network.flush_all_rules(master_ip())
    network.allow_all_traffic(master_ip())

    if incoming and outgoing:
        network.run_iptables(master_ip(), DISABLE_MASTER_INCOMING)
        network.run_iptables(master_ip(), DISABLE_MASTER_OUTGOING)
    elif incoming:
        network.run_iptables(master_ip(), DISABLE_MASTER_INCOMING)
    elif outgoing:
        network.run_iptables(master_ip(), DISABLE_MASTER_OUTGOING)
    else:
        pass


def reconnect_master():
    """ Reconnect a previously partitioned master to the network
    """
    network.restore_iptables(master_ip())


def restart_master_node():
    """ Restarts the master node
    """

    run_command_on_master("sudo /sbin/shutdown -r now")


def systemctl_master(command='restart'):
    """ Used to start, stop or restart the master process
    """
    run_command_on_master('sudo systemctl {} dcos-mesos-master'.format(command))


def mesos_available_predicate():
    url = master_url()
    auth = DCOSAcsAuth(dcos_acs_token())
    try:
        response = requests.get(url(), auth=auth, verify=verify_ssl())
        return response.status_code == 200
    except Exception:
        return False


def wait_for_mesos_endpoint(timeout_sec=timedelta(minutes=5).total_seconds()):
    """Checks the service url if available it returns true, on expiration
    it returns false"""
    assert_that(lambda: mesos_available_predicate(), eventually(equal_to(True)))


def _mesos_zk_nodes():
    """ Returns all the children nodes under /mesos in zk
    """
    return get_zk_node_children('/mesos')


def _master_zk_nodes_keys():
    """ The masters can be registered in zk with arbitrary ids which start with
        `json.info_`.  This provides a list of all master keys.
    """
    master_zk = []
    for node in _mesos_zk_nodes():
        if 'json.info' in node['title']:
            master_zk.append(node['key'])

    return master_zk


def get_all_masters():
    """ Returns the json object that represents each of the masters.
    """
    masters = []
    for master in _master_zk_nodes_keys():
        master_zk_str = get_zk_node_data(master)['str']
        masters.append(json.loads(master_zk_str))

    return masters


def get_all_master_ips():
    """ Returns a list of IPs for the masters
    """
    ips = []
    for master in get_all_masters():
        ips.append(master['hostname'])

    return ips


def is_multi_master():
    master_count = len(get_all_masters())
    return master_count > 1


def required_masters(count):
    """ Returns True if the number of private agents is equal to or greater than
    the count.  This is useful in using pytest skipif such as:
    `pytest.mark.skipif('required_masters(3)')` which will skip the test if
    the number of masters is only 1.

    :param count: the number of required masters.
    """
    master_count = len(get_all_masters())
    # reverse logic (skip if less than count)
    # returns True if less than count
    return master_count < count


def masters(count=1):
    return pytest.mark.skipif('required_masters({})'.format(count))


def start_master_http_service(port=7777, pid_file='python_http.pid'):
    """ Starts a http service on the master leader.  The main purpose is to serve
    up artifacts for launched test applications.   This is commonly used in combination
    with copying tests or artifacts to the leader than configuring the messos task
    to fetch from http://master.mesos:7777/artifact.tar (becareful in a multi-master env)

    :param port: port to use for the http service
    :return: pid_file
    """
    run_command_on_master(
        'nohup /opt/mesosphere/bin/python -m http.server {} > http.log 2>&1 & '
        'echo $! > {}'.format(port, pid_file))
    return pid_file


@contextlib.contextmanager
def master_http_service(port=7777):
    pid_file = start_master_http_service(port)
    yield
    kill_process_from_pid_file_on_host(master_ip(), pid_file)


@contextlib.contextmanager
def disconnected_master(incoming=True, outgoing=True):

    partition_master(incoming, outgoing)
    try:
        yield
    finally:
        # return config to previous state
        reconnect_master()
