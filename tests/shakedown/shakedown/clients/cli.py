import logging
import shlex
import subprocess

from contextlib import contextmanager

from . import dcos_url
from ..errors import DCOSException


logger = logging.getLogger(__name__)


@contextmanager
def attached_cli():
    """ Attaches the local dcos-cli to the clusters.

    This assumes that DCOS_URL, DCOS_PASSWORD and DCOS_USERNAME are set.

    The CLI setup command should be idempotent. So it is save to call this method multiple times.
    """
    cmd = 'cluster setup {} --no-check '.format(dcos_url())
    run_dcos_command(cmd)
    yield


def run_dcos_command(command, raise_on_error=False, print_output=True):
    """ Run `dcos {command}` via DC/OS CLI

        :param command: the command to execute
        :type command: str
        :param raise_on_error: whether to raise a DCOSException if the return code is nonzero
        :type raise_on_error: bool
        :param print_output: whether to print the resulting stdout/stderr from running the command
        :type print_output: bool

        :return: (stdout, stderr, return_code)
        :rtype: tuple
    """

    call = shlex.split(command)
    call.insert(0, 'dcos')

    print("\n>>{}\n".format(' '.join(call)))

    proc = subprocess.Popen(call, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    print('start communication')
    output, error = proc.communicate()
    print('wait for return code...')
    return_code = proc.wait()
    stdout = output.decode('utf-8')
    stderr = error.decode('utf-8')

    if print_output:
        print(stdout, stderr, return_code)

    if return_code != 0 and raise_on_error:
        raise DCOSException('Got error code {} when running command "dcos {}":\nstdout: "{}"\nstderr: "{}"'.format(
                            return_code, command, stdout, stderr))

    return stdout, stderr, return_code
