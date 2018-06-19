import os
import scp
import time

from shakedown.dcos.helpers import *

import shakedown


def copy_file(
        host,
        file_path,
        remote_path='.',
        username=None,
        key_path=None,
        action='put'
):
    """ Copy a file via SCP, proxied through the mesos master

        :param host: host or IP of the machine to execute the command on
        :type host: str
        :param file_path: the local path to the file to be copied
        :type file_path: str
        :param remote_path: the remote path to copy the file to
        :type remote_path: str
        :param username: SSH username
        :type username: str
        :param key_path: path to the SSH private key to use for SSH authentication
        :type key_path: str

        :return: True if successful, False otherwise
        :rtype: bool
    """

    if not username:
        username = shakedown.cli.ssh_user

    if not key_path:
        key_path = shakedown.cli.ssh_key_file

    key = validate_key(key_path)

    transport = get_transport(host, username, key)
    transport = start_transport(transport, username, key)

    if transport.is_authenticated():
        start = time.time()

        channel = scp.SCPClient(transport)

        if action == 'get':
            print("\n{}scp {}:{} {}\n".format(shakedown.cli.helpers.fchr('>>'), host, remote_path, file_path))
            channel.get(remote_path, file_path)
        else:
            print("\n{}scp {} {}:{}\n".format(shakedown.cli.helpers.fchr('>>'), file_path, host, remote_path))
            channel.put(file_path, remote_path)

        print("{} bytes copied in {} seconds.".format(str(os.path.getsize(file_path)), str(round(time.time() - start, 2))))

        try_close(channel)
        try_close(transport)

        return True
    else:
        print("error: unable to authenticate {}@{} with key {}".format(username, host, key_path))
        return False


def copy_file_to_master(
        file_path,
        remote_path='.',
        username=None,
        key_path=None
):
    """ Copy a file to the Mesos master
    """

    return copy_file(shakedown.master_ip(), file_path, remote_path, username, key_path)


def copy_file_to_agent(
        host,
        file_path,
        remote_path='.',
        username=None,
        key_path=None
):
    """ Copy a file to a Mesos agent, proxied through the master
    """

    return copy_file(host, file_path, remote_path, username, key_path)


def copy_file_from_master(
        remote_path,
        file_path='.',
        username=None,
        key_path=None
):
    """ Copy a file to the Mesos master
    """

    return copy_file(shakedown.master_ip(), file_path, remote_path, username, key_path, 'get')


def copy_file_from_agent(
        host,
        remote_path,
        file_path='.',
        username=None,
        key_path=None
):
    """ Copy a file to a Mesos agent, proxied through the master
    """

    return copy_file(host, file_path, remote_path, username, key_path, 'get')
