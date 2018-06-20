import os
import paramiko
import scp

from dcos import http

import itertools
import shakedown


def get_transport(host, username, key):
    """ Create a transport object

        :param host: the hostname to connect to
        :type host: str
        :param username: SSH username
        :type username: str
        :param key: key object used for authentication
        :type key: paramiko.RSAKey

        :return: a transport object
        :rtype: paramiko.Transport
    """

    if host == shakedown.master_ip():
        transport = paramiko.Transport(host)
    else:
        transport_master = paramiko.Transport(shakedown.master_ip())
        transport_master = start_transport(transport_master, username, key)

        if not transport_master.is_authenticated():
            print("error: unable to authenticate {}@{} with key {}".format(username, shakedown.master_ip(), key))
            return False

        try:
            channel = transport_master.open_channel('direct-tcpip', (host, 22), ('127.0.0.1', 0))
        except paramiko.SSHException:
            print("error: unable to connect to {}".format(host))
            return False

        transport = paramiko.Transport(channel)

    return transport


def start_transport(transport, username, key):
    """ Begin a transport client and authenticate it

        :param transport: the transport object to start
        :type transport: paramiko.Transport
        :param username: SSH username
        :type username: str
        :param key: key object used for authentication
        :type key: paramiko.RSAKey

        :return: the transport object passed
        :rtype: paramiko.Transport
    """

    transport.start_client()

    agent = paramiko.agent.Agent()
    keys = itertools.chain((key,) if key else (), agent.get_keys())
    for test_key in keys:
        try:
            transport.auth_publickey(username, test_key)
            break
        except paramiko.AuthenticationException as e:
            pass
    else:
        raise ValueError('No valid key supplied')

    return transport


# SSH connection will be auto-terminated at the conclusion of this operation, causing
# a race condition; the try/except block attempts to close the channel and/or transport
# but does not issue a failure if it has already been closed.
def try_close(obj):
    try:
        obj.close()
    except:
        pass


def validate_key(key_path):
    """ Validate a key

        :param key_path: path to a key to use for authentication
        :type key_path: str

        :return: key object used for authentication
        :rtype: paramiko.RSAKey
    """

    key_path = os.path.expanduser(key_path)

    if not os.path.isfile(key_path):
        return False

    return paramiko.RSAKey.from_private_key_file(key_path)
