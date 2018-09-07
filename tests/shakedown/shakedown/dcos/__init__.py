import os
import dcos
import sys

import shakedown

from shakedown import http
from shakedown.clients import mesos, gen_url


def attach_cluster(url):
    """Attach to an already set-up cluster
    :return: True if successful, else False
    """
    with shakedown.stdchannel_redirected(sys.stderr, os.devnull):
        clusters = [c.dict() for c in dcos.cluster.get_clusters()]
    for c in clusters:
        if url == c['url']:
            try:
                dcos.cluster.set_attached(dcos.cluster.get_cluster(c['name']).get_cluster_path())
                return True
            except Exception:
                return False

    return False


def master_url():
    """Return the URL of a master running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return gen_url("/mesos/")


def agents_url():
    """Return the URL of a master agents running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return gen_url("/mesos/slaves")


def dcos_state():
    client = mesos.DCOSClient()
    json_data = client.get_state_summary()

    if json_data:
        return json_data
    else:
        return None


def dcos_agents_state():
    response = http.get(agents_url())

    if response.status_code == 200:
        return response.json()
    else:
        return None


def dcos_leader():
    return dcos_dns_lookup('leader.mesos.')


def dcos_dns_lookup(name):
    return mesos.MesosDNSClient().hosts(name)


def dcos_version():
    """Return the version of the running cluster.
    :return: DC/OS cluster version as a string
    """
    url = gen_url('dcos-metadata/dcos-version.json')
    response = http.request('get', url)

    if response.status_code == 200:
        return response.json()['version']
    else:
        return None


def master_ip():
    """Returns the public IP address of the DC/OS master.
    return: DC/OS IP address as a string
    """
    return mesos.DCOSClient().metadata().get('PUBLIC_IPV4')


def master_leader_ip():
    """Returns the private IP of the mesos master leader.
    In a multi-master cluster this may not map to the public IP of the master_ip.
    """
    return dcos_dns_lookup('leader.mesos')[0]['ip']


def marathon_leader_ip():
    """Returns the private IP of the marathon leader.
    """
    return dcos_dns_lookup('marathon.mesos')[0]['ip']
