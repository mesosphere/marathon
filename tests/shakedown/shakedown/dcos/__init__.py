import os
import dcos
import dcos.cluster
import sys

import shakedown


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
            except:
                return False

    return False


def dcos_acs_token():
    """Return the DC/OS ACS token as configured in the DC/OS library.
    :return: DC/OS ACS token as a string
    """
    return dcos.config.get_config().get('core.dcos_acs_token')


def dcos_url():
    """Return the DC/OS URL as configured in the DC/OS library. This is
    equivalent to the value of '--dcos_url' passed into Shakedown on the
    command line.
    :return: DC/OS cluster URL as a string
    """
    return dcos.config.get_config().get('core.dcos_url')


def dcos_service_url(service):
    """Return the URL of a service running on DC/OS, based on the value of
    shakedown.dcos.dcos_url() and the service name.
    :param service: the name of a registered DC/OS service, as a string
    :return: the full DC/OS service URL, as a string
    """
    return _gen_url("/service/{}/".format(service))


def master_url():
    """Return the URL of a master running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return _gen_url("/mesos/")


def agents_url():
    """Return the URL of a master agents running on DC/OS, based on the value of
    shakedown.dcos.dcos_url().
    :return: the full DC/OS master URL, as a string
    """
    return _gen_url("/mesos/slaves")


def dcos_state():
    client = dcos.mesos.DCOSClient()
    json_data = client.get_state_summary()

    if json_data:
        return json_data
    else:
        return None


def dcos_agents_state():
    response = dcos.http.get(agents_url())

    if response.status_code == 200:
        return response.json()
    else:
        return None


def dcos_leader():
    return dcos_dns_lookup('leader.mesos.')


def dcos_dns_lookup(name):
    return dcos.mesos.MesosDNSClient().hosts(name)


def dcos_version():
    """Return the version of the running cluster.
    :return: DC/OS cluster version as a string
    """
    url = _gen_url('dcos-metadata/dcos-version.json')
    response = dcos.http.request('get', url)

    if response.status_code == 200:
        return response.json()['version']
    else:
        return None


def master_ip():
    """Returns the public IP address of the DC/OS master.
    return: DC/OS IP address as a string
    """
    return dcos.mesos.DCOSClient().metadata().get('PUBLIC_IPV4')


def authenticate(username, password):
    """Authenticate with a DC/OS cluster and return an ACS token.
    return: ACS token
    """
    url = _gen_url('acs/api/v1/auth/login')

    creds = {
        'uid': username,
        'password': password
    }

    response = dcos.http.request('post', url, json=creds)

    if response.status_code == 200:
        return response.json()['token']
    else:
        return None


def authenticate_oauth(oauth_token):
    """Authenticate by checking for a valid OAuth token.
    return: ACS token
    """
    url = _gen_url('acs/api/v1/auth/login')

    creds = {
        'token': oauth_token
    }

    response = dcos.http.request('post', url, json=creds)

    if response.status_code == 200:
        return response.json()['token']
    else:
        return None


def dcos_url_path(url_path):
    return _gen_url(url_path)


def _gen_url(url_path):
    """Return an absolute URL by combining DC/OS URL and url_path.

    :param url_path: path to append to DC/OS URL
    :type url_path: str
    :return: absolute URL
    :rtype: str
    """
    from six.moves import urllib
    return urllib.parse.urljoin(dcos_url(), url_path)
