import logging
import time
from _thread import RLock
from functools import lru_cache, wraps
from os import environ
from select import select

import paramiko

from . import master_ip, master_leader_ip, marathon_leader_ip
from .helpers import validate_key, try_close, get_transport, start_transport
from ..errors import DCOSException


logger = logging.getLogger(__name__)


@lru_cache()
def ssh_key_file():
    if 'SHAKEDOWN_SSH_KEY_FILE' in environ:
        return environ.get('SHAKEDOWN_SSH_KEY_FILE')
    else:
        raise DCOSException('SHAKEDOWN_SSH_KEY_FILE environment variable is not defined.')


@lru_cache()
def ssh_user():
    if 'SHAKEDOWN_SSH_USER' in environ:
        return environ.get('SHAKEDOWN_SSH_USER')
    else:
        raise DCOSException('SHAKEDOWN_SSH_USER environment variable is not defined.')


def connection_cache(func: callable):
    """Connection cache for SSH sessions. This is to prevent opening a
     new, expensive connection on every command run."""
    cache = dict()
    lock = RLock()

    @wraps(func)
    def func_wrapper(host: str, username: str, *args, **kwargs):
        key = "{h}-{u}".format(h=host, u=username)
        if key in cache:
            # connection exists, check if it is still valid before
            # returning it.
            conn = cache[key]
            if conn and conn.is_active() and conn.is_authenticated():
                return conn
            else:
                # try to close a bad connection and remove it from
                # the cache.
                if conn:
                    try_close(conn)
                del cache[key]

        # key is not in the cache, so try to recreate it
        # it may have been removed just above.
        if key not in cache:
            conn = func(host, username, *args, **kwargs)
            if conn is not None:
                cache[key] = conn
            return conn

        # not sure how to reach this point, but just in case.
        return None

    def get_cache() -> dict:
        return cache

    def purge(key: str = None):
        with lock:
            if key is None:
                conns = [(k, v) for k, v in cache.items()]
            elif key in cache:
                conns = ((key, cache[key]), )
            else:
                conns = list()

            for k, v in conns:
                try_close(v)
                del cache[k]

    func_wrapper.get_cache = get_cache
    func_wrapper.purge = purge
    return func_wrapper


@connection_cache
def _get_connection(host, username: str, key_path: str) \
        -> paramiko.Transport or None:
    """Return an authenticated SSH connection.

    :param host: host or IP of the machine
    :type host: str
    :param username: SSH username
    :type username: str
    :param key_path: path to the SSH private key for SSH auth
    :type key_path: str
    :return: SSH connection
    :rtype: paramiko.Transport or None
    """
    if not username:
        username = ssh_user()
    if not key_path:
        key_path = ssh_key_file()
    key = validate_key(key_path)
    transport = get_transport(host, username, key)

    if transport:
        transport = start_transport(transport, username, key)
        if transport.is_authenticated():
            return transport
        else:
            print("error: unable to authenticate {}@{} with key {}".format(username, host, key_path))
    else:
        print("error: unable to connect to {}".format(host))

    return None


def run_command(
        host,
        command,
        username=None,
        key_path=None,
        noisy=True
):
    """ Run a command via SSH, proxied through the mesos master

        :param host: host or IP of the machine to execute the command on
        :type host: str
        :param command: the command to execute
        :type command: str
        :param username: SSH username
        :type username: str
        :param key_path: path to the SSH private key to use for SSH authentication
        :type key_path: str
        :return: True if successful, False otherwise
        :rtype: bool
        :return: Output of command
        :rtype: string
    """

    with HostSession(host, username, key_path, noisy) as s:
        print("\n>>{} $ {}\n".format(host, command))
        s.run(command)

    ec, output = s.get_result()
    return ec == 0, output


def run_command_on_master(
        command,
        username=None,
        key_path=None,
        noisy=True
):
    """ Run a command on the Mesos master
    """

    return run_command(master_ip(), command, username, key_path, noisy)


def run_command_on_leader(
        command,
        username=None,
        key_path=None,
        noisy=True
):
    """ Run a command on the Mesos leader.  Important for Multi-Master.
    """

    return run_command(master_leader_ip(), command, username, key_path, noisy)


def run_command_on_marathon_leader(
        command,
        username=None,
        key_path=None,
        noisy=True
):
    """ Run a command on the Marathon leader
    """

    return run_command(marathon_leader_ip(), command, username, key_path, noisy)


def run_command_on_agent(
        host,
        command,
        username=None,
        key_path=None,
        noisy=True
):
    """ Run a command on a Mesos agent, proxied through the master
    """

    return run_command(host, command, username, key_path, noisy)


class HostSession:
    """Context manager that returns an SSH session, reusing authenticated connections.
    """
    def __init__(self, host, username, key_path, verbose):
        self.host = host
        self.username = username
        self.key_path = key_path
        self.verbose = verbose
        self.exit_code = -1
        self.output = ''
        self.session = None

    def __enter__(self):
        """
        :return: this session manager
        :rtype: HostSession
        """
        c = _get_connection(self.host, self.username, self.key_path)
        if c:
            self.session = c.open_session()

        return self

    def __exit__(self, *args):
        """Executed when the context manager is complete.

        :return: None
        """
        self.exit_code = self.session.recv_exit_status()
        self._wait_for_recv()
        # read data that is ready
        while self.session.recv_ready():
            # lists of file descriptors that are ready for IO
            # read, write, "exceptional condition" (?)
            rl, wl, xl = select([self.session], [], [], 0.0)
            if len(rl) > 0:
                recv = str(self.session.recv(1024), "utf-8")
                if self.verbose:
                    print(recv, end='', flush=True)
                self.output += recv
        try_close(self.session)
        # no Exceptions were handled; return False
        return False

    def _wait_for_recv(self):
        """After executing a command, wait for results.

        Because `recv_ready()` can return False, but still have a
        valid, open connection, it is not enough to ensure output
        from a command execution is properly captured.

        :return: None
        """
        while True:
            time.sleep(0.2)
            if self.session.recv_ready() or self.session.closed:
                return

    def run(self, command):
        """Run `command` on this SSH session. This does not return the
        result, use `get_result` to retrieve command's results.

        :param command: SSH command to run
        :type command: str

        :return: None
        """
        self.session.exec_command(command)

    def get_result(self):
        return self.exit_code, self.output
