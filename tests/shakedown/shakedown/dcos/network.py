"""Utilities for working with nodes
    There are a number of common utilies for working with agents and master nodes
    which are provided here.
"""
from shakedown import *


def restore_iptables(host):
    """ Reconnect a previously partitioned node to the network
        :param hostname: host or IP of the machine to partition from the cluster
    """

    run_command_on_agent(host, 'if [ -e iptables.rules ]; then sudo iptables-restore < iptables.rules && rm iptables.rules ; fi')


def save_iptables(host):
    """ Saves iptables firewall rules such they can be restored
    """

    run_command_on_agent(host, 'if [ ! -e iptables.rules ] ; then sudo iptables -L > /dev/null && sudo iptables-save > iptables.rules ; fi')


def run_iptables(host, rule):
    """ iptables is challenging to abstract.  This function takes a rule
        '-I INPUT -p tcp --dport 22 -j ACCEPT' and runs it on the agent.
    """
    ip_table_cmd = 'sudo iptables {}'.format(rule)
    run_command_on_agent(host, ip_table_cmd)


def flush_all_rules(host):
    """ Flushes all the iptables rules
    """
    run_command_on_agent(host, 'sudo iptables -F INPUT')


def allow_all_traffic(host):
    """ Opens up iptables on host to allow all traffic
    """
    run_command_on_agent(host, 'sudo iptables --policy INPUT ACCEPT && sudo iptables --policy OUTPUT ACCEPT && sudo iptables --policy FORWARD ACCEPT')


@contextlib.contextmanager
def iptable_rules(host):
    save_iptables(host)
    try:
        yield
    finally:
        # return config to previous state
        restore_iptables(host)
