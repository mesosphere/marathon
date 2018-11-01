import requests

from ..clients import mesos, dcos_url_path
from ..clients.authentication import dcos_acs_token, DCOSAcsAuth
from ..clients.rpcclient import verify_ssl


def master_url():
    """Return the URL of a master running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return dcos_url_path("/mesos/")


def agents_url():
    """Return the URL of a master agents running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return dcos_url_path("/mesos/slaves")


def dcos_state():
    client = mesos.DCOSClient()
    json_data = client.get_state_summary()

    if json_data:
        return json_data
    else:
        return None


# TODO(karsten): Use Mesos client instead.
def dcos_agents_state():
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.get(agents_url(), auth=auth, verify=verify_ssl())

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
    url = dcos_url_path('dcos-metadata/dcos-version.json')
    auth = DCOSAcsAuth(dcos_acs_token())
    response = requests.get(url, auth=auth, verify=verify_ssl())

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
