from __future__ import print_function

import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import zipfile
from distutils.version import LooseVersion

from dcos import config, constants, http, util
from dcos.errors import DCOSException
from dcos.subprocess import Subproc

logger = util.get_logger(__name__)


def command_executables(subcommand):
    """List the real path to executable dcos program for specified subcommand.

    :param subcommand: name of subcommand. E.g. marathon
    :type subcommand: str
    :returns: the dcos program path
    :rtype: str
    """

    executables = []
    if subcommand in default_subcommands():
        executables += [default_list_paths()]

    executables += [
        command_path
        for command_path in list_paths()
        if noun(command_path) == subcommand
    ]

    if len(executables) > 1:
        msg = 'Found more than one executable for command {!r}. {!r}'
        raise DCOSException(msg.format(subcommand, executables))

    if len(executables) == 0:
        msg = "{!r} is not a dcos command."
        raise DCOSException(msg.format(subcommand))

    return executables[0]


def get_package_commands(package_name):
    """List the real path(s) to executables for a specific dcos subcommand

    :param package_name: package name
    :type package_name: str
    :returns: list of all the dcos program paths in package
    :rtype: [str]
    """

    bin_dir = os.path.join(_package_dir(package_name),
                           constants.DCOS_SUBCOMMAND_ENV_SUBDIR,
                           BIN_DIRECTORY)

    executables = []
    for filename in os.listdir(bin_dir):
        path = os.path.join(bin_dir, filename)

        if (filename.startswith(constants.DCOS_COMMAND_PREFIX) and
                _is_executable(path)):

            executables.append(path)

    return executables


def default_list_paths():
    """List the real path to dcos executable

    :returns: list dcos program path
    :rtype: str
    """

    # Let's get all the default subcommands
    binpath = util.dcos_bin_path()
    return os.path.join(binpath, "dcos")


def list_paths():
    """List the real path to executable dcos subcommand programs.

    :returns: list of all the dcos program paths
    :rtype: [str]
    """

    subcommands = []
    for package in distributions():
        subcommands += get_package_commands(package)

    return subcommands


def _is_executable(path):
    """
    :param path: the path to a program
    :type path: str
    :returns: True if the path is an executable; False otherwise
    :rtype: bool
    """

    return os.access(path, os.X_OK) and (
        not util.is_windows_platform() or path.endswith('.exe'))


def _find_distributions(subcommand_dir):
    """
    :param subcommand_dir: directory to find packaged in
    :type subcommand_dir: path
    :returns: list of all installed subcommands in given directory
    :rtype: [str]
    """

    if subcommand_dir and os.path.isdir(subcommand_dir):
        return [
            subdir for subdir in os.listdir(subcommand_dir)
            if os.path.isdir(
                os.path.join(
                    subcommand_dir,
                    subdir,
                    constants.DCOS_SUBCOMMAND_ENV_SUBDIR))
        ]
    else:
        return []


def distributions():
    """Set of all of the installed subcommand packages

    :returns: a set of packages
    :rtype: Set[str]
    """

    cluster_packages = _find_distributions(_cluster_subcommand_dir())
    global_packages = _find_distributions(global_subcommand_dir())
    return set(cluster_packages + global_packages)


# must also add subcommand name to dcoscli.subcommand._default_modules
def default_subcommands():
    """List the default dcos cli subcommands

    :returns: list of all the default dcos cli subcommands
    :rtype: [str]
    """

    return ["auth", "cluster", "config", "experimental", "help", "job",
            "marathon", "node", "package", "service", "task"]


def documentation(executable_path):
    """Gather subcommand summary

    :param executable_path: real path to the dcos subcommands
    :type executable_path: str
    :returns: subcommand and its summary
    :rtype: (str, str)
    """

    path_noun = noun(executable_path)
    return (path_noun, info(executable_path, path_noun))


def info(executable_path, path_noun):
    """Collects subcommand information

    :param executable_path: real path to the dcos subcommand
    :type executable_path: str
    :param path_noun: subcommand
    :type path_noun: str
    :returns: the subcommand information
    :rtype: str
    """

    out = Subproc().check_output(
        [executable_path, path_noun, '--info'])

    return out.decode('utf-8').strip()


def config_schema(executable_path, noun=None):
    """Collects subcommand config schema

    :param executable_path: real path to the dcos subcommand
    :type executable_path: str
    :param noun: name of subcommand
    :type noun: str
    :returns: the subcommand config schema
    :rtype: dict
    """
    if noun is None:
        noun = noun(executable_path)

    out = Subproc().check_output(
        [executable_path, noun, '--config-schema'])

    return json.loads(out.decode('utf-8'))


def noun(executable_path):
    """Extracts the subcommand single noun from the path to the executable.
    E.g for :code:`bin/dcos-subcommand` this method returns :code:`subcommand`.

    :param executable_path: real pth to the dcos subcommand
    :type executable_path: str
    :returns: the subcommand
    :rtype: str
    """

    basename = os.path.basename(executable_path)
    noun = basename[len(constants.DCOS_COMMAND_PREFIX):].replace('.exe', '')
    return noun


def _write_package_json(pkg, pkg_dir):
    """ Write package.json locally.

    :param pkg: the package being installed
    :type pkg: PackageVersion
    :param pkg_dir: directory to install package
    :type pkg_dir: str
    :rtype: None
    """

    package_path = os.path.join(pkg_dir, 'package.json')

    package_json = pkg.package_json()

    with util.open_file(package_path, 'w') as package_file:
        json.dump(package_json, package_file)


def _hashfile(filename):
    """Calculates the sha256 of a file

    :param filename: path to the file to sum
    :type filename: str
    :returns: digest in hexadecimal
    :rtype: str
    """

    hasher = hashlib.sha256()
    with open(filename, 'rb') as f:
        for chunk in iter(lambda: f.read(4096), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def _check_hash(filename, content_hashes):
    """Validates whether downloaded binary matches expected hash

    :param filename: path to binary
    :type filename: str
    :param content_hashes: list of hash algorithms/value
    :type content_hashes: [{"algo": <str>, "value": <str>}]
    :returns: None if valid hash, else throws exception
    :rtype: None
    """

    content_hash = next((contents for contents in content_hashes
                        if contents.get("algo") == "sha256"),
                        None)
    if content_hash:
        expected_value = content_hash.get("value")
        actual_value = _hashfile(filename)
        if expected_value != actual_value:
            raise DCOSException(
                "The hash for the downloaded subcommand [{}] "
                "does not match the expected value [{}]. Aborting...".format(
                    actual_value, expected_value))
        else:
            return
    else:
        raise DCOSException(
            "Hash algorithm specified is unsupported. "
            "Please contact the package maintainer. Aborting...")


def _get_cli_binary_info(cli_resources):
    """Find compatible cli binary, if one exists

    :param cli_resources: cli property of resource.json
    :type resources: {}
    :returns: {"url": <str>, "kind": <str>, "contentHash": [{}]}
    :rtype: {} | None
    """

    if "binaries" in cli_resources:
        binaries = cli_resources["binaries"]
        arch = platform.architecture()[0]
        if arch != "64bit":
            raise DCOSException(
                "There is no compatible subcommand for your architecture [{}] "
                "We only support x86-64. Aborting...".format(arch))
        system = platform.system().lower()
        binary = binaries.get(system)
        if binary is None:
            raise DCOSException(
                "There is not compatible subcommand for your system [{}] "
                "Aborting...".format(system))
        elif "x86-64" in binary:
            return binary["x86-64"]

    raise DCOSException(
        "The CLI subcommand has unexpected format [{}]. "
        "Please contact the package maintainer. Aborting...".format(
            cli_resources))


def _install_cli(pkg, pkg_dir):
    """Install subcommand cli

    :param pkg: the package to install
    :type pkg: PackageVersion
    :param pkg_dir: directory to install package
    :type pkg_dir: str
    :rtype: None
    """

    with util.remove_path_on_error(pkg_dir) as pkg_dir:
        env_dir = os.path.join(pkg_dir, constants.DCOS_SUBCOMMAND_ENV_SUBDIR)

        resources = pkg.resource_json()

        if resources and resources.get("cli") is not None:
            binary = resources["cli"]
            binary_cli = _get_cli_binary_info(binary)
            _install_with_binary(
                pkg.name(),
                env_dir,
                binary_cli)
        elif pkg.command_json() is not None:
            install_operation = pkg.command_json()
            if 'pip' in install_operation:
                _install_with_pip(
                    pkg.name(),
                    env_dir,
                    install_operation['pip'])
            else:
                raise DCOSException(
                    "Installation methods '{}' not supported".format(
                        install_operation.keys()))
        else:
            raise DCOSException(
                "Could not find a CLI subcommand for your platform")


def install(pkg, global_=False):
    """Installs the dcos cli subcommand

    :param pkg: the package to install
    :type pkg: Package
    :param global_: whether to install the CLI globally
    :type global_: bool
    :rtype: None
    """

    if global_ or config.uses_deprecated_config():
        pkg_dir = global_package_dir(pkg.name())
    else:
        pkg_dir = _cluster_package_dir(pkg.name())

    util.ensure_dir_exists(pkg_dir)

    _write_package_json(pkg, pkg_dir)

    _install_cli(pkg, pkg_dir)


def global_subcommand_dir():
    """ Returns global subcommand dir. defaults to ~/.dcos/subcommands """

    return os.path.join(config.get_config_dir_path(),
                        constants.DCOS_SUBCOMMAND_SUBDIR)


def _cluster_subcommand_dir():
    """
    :returns: cluster specific subcommand dir or None
    :rtype: str | None
    """

    attached_cluster = config.get_attached_cluster_path()
    if attached_cluster is not None:
        return os.path.join(
            attached_cluster, constants.DCOS_SUBCOMMAND_SUBDIR)
    else:
        return None


def _cluster_package_dir(name):
    """Returns path to package directory for attached cluster

    :param name: package name
    :type name: str
    :returns: path to package directory
    :rtype: str | None
    """

    subcommand_dir = _cluster_subcommand_dir()
    if subcommand_dir is not None:
        return os.path.join(subcommand_dir, name)
    else:
        return None


def global_package_dir(name):
    """Returns path to package directory in global config

    :param name: package name
    :type name: str
    :rtype: str
    """

    return os.path.join(global_subcommand_dir(), name)


def _package_dir(name):
    """Returns cluster subcommand dir for name if exists, and if not
    returns path to global subcommand dir.

    :param name: package name
    :type name: str
    :rtype: str
    """

    cluster_subcommand = _cluster_package_dir(name)
    if cluster_subcommand and os.path.exists(cluster_subcommand):
        return cluster_subcommand
    else:
        return global_package_dir(name)


def uninstall(package_name):
    """Uninstall the dcos cli subcommand

    :param package_name: the name of the package
    :type package_name: str
    :returns: True if the subcommand was uninstalled
    :rtype: bool
    """

    pkg_dir = _package_dir(package_name)

    if os.path.isdir(pkg_dir):
        shutil.rmtree(pkg_dir)
        return True

    return False


BIN_DIRECTORY = 'Scripts' if util.is_windows_platform() else 'bin'


def _find_virtualenv(bin_directory):
    """
    :param bin_directory: directory to first use to find virtualenv
    :type bin_directory: str
    :returns: Absolute path to virutalenv program
    :rtype: str
    """

    virtualenv_path = os.path.join(bin_directory, 'virtualenv')
    if not os.path.exists(virtualenv_path):
        virtualenv_path = util.which('virtualenv')

    if virtualenv_path is None:
        msg = ("Unable to install CLI subcommand. "
               "Missing required program 'virtualenv'.\n"
               "Please see installation instructions: "
               "https://virtualenv.pypa.io/en/latest/installation.html")
        raise DCOSException(msg)

    return virtualenv_path


def _download_and_store(url, location):
    """Download given url and store in location on disk

    :param url: url to download
    :type url: str
    :param location: path to file to store url
    :type location: str
    :rtype: None
    """

    with open(location, 'wb') as f:
        r = http.get(url, stream=True)
        for chunk in r.iter_content(1024):
            f.write(chunk)


def _install_with_binary(
        package_name,
        env_directory,
        binary_cli):
    """
    :param package_name: the name of the package
    :type package_name: str
    :param env_directory: the path to the directory in which to install the
                          package's binary_cli
    :type env_directory: str
    :param binary_cli: binary cli to install
    :type binary_cli: str
    :rtype: None
    """

    binary_url, kind = binary_cli.get("url"), binary_cli.get("kind")

    try:
        env_bin_dir = os.path.join(env_directory, BIN_DIRECTORY)

        if kind in ["executable", "zip"]:
            with util.temptext() as file_tmp:
                _, binary_tmp = file_tmp
                _download_and_store(binary_url, binary_tmp)
                _check_hash(binary_tmp, binary_cli.get("contentHash"))

                if kind == "executable":
                    util.ensure_dir_exists(env_bin_dir)
                    binary_name = "dcos-{}".format(package_name)
                    if util.is_windows_platform():
                        binary_name += '.exe'
                    binary_file = os.path.join(env_bin_dir, binary_name)

                    # copy to avoid windows error of moving open file
                    # binary_tmp will be removed by context manager
                    shutil.copy(binary_tmp, binary_file)
                else:
                    # kind == "zip"
                    with zipfile.ZipFile(binary_tmp) as zf:
                        zf.extractall(env_directory)

            # check contents for package_name/env/bin folder structure
            if not os.path.exists(env_bin_dir):
                msg = (
                    "CLI subcommand for [{}] has an unexpected format. "
                    "Please contact the package maintainer".format(
                        package_name))
                raise DCOSException(msg)
        else:
            msg = ("CLI subcommand for [{}] is an unsupported type: {}"
                   "Please contact the package maintainer".format(
                       package_name, kind))
            raise DCOSException(msg)

        # make binar(ies) executable
        for f in os.listdir(env_bin_dir):
            binary = os.path.join(env_bin_dir, f)
            if (f.startswith(constants.DCOS_COMMAND_PREFIX)):
                st = os.stat(binary)
                os.chmod(binary, st.st_mode | stat.S_IEXEC)
    except DCOSException:
        raise
    except Exception as e:
        logger.exception(e)
        raise _generic_error(package_name, e.message)

    return None


def _install_with_pip(
        package_name,
        env_directory,
        requirements):
    """
    :param package_name: the name of the package
    :type package_name: str
    :param env_directory: the path to the directory in which to install the
                          package's virtual env
    :type env_directory: str
    :param requirements: the list of pip requirements
    :type requirements: list of str
    :rtype: None
    """

    bin_directory = util.dcos_bin_path()
    new_package_dir = not os.path.exists(env_directory)

    pip_path = os.path.join(env_directory, BIN_DIRECTORY, 'pip')
    if not os.path.exists(pip_path):
        virtualenv_path = _find_virtualenv(bin_directory)

        virtualenv_version = _execute_command(
            [virtualenv_path, '--version'])[0].strip().decode('utf-8')
        if LooseVersion("12") > LooseVersion(virtualenv_version):
            msg = ("Unable to install CLI subcommand. "
                   "Required program 'virtualenv' must be version 12+, "
                   "currently version {}\n"
                   "Please see installation instructions: "
                   "https://virtualenv.pypa.io/en/latest/installation.html"
                   "".format(virtualenv_version))
            raise DCOSException(msg)

        cmd = [_find_virtualenv(bin_directory), env_directory]

        if _execute_command(cmd)[2] != 0:
            raise _generic_error(package_name)

    # Do not replace util.temptext NamedTemporaryFile
    # otherwise bad things will happen on Windows
    with util.temptext() as text_file:
        fd, requirement_path = text_file

        # Write the requirements to the file
        with os.fdopen(fd, 'w') as requirements_file:
            for line in requirements:
                print(line, file=requirements_file)

        cmd = [
            os.path.join(env_directory, BIN_DIRECTORY, 'pip'),
            'install',
            '--requirement',
            requirement_path,
        ]

        if _execute_command(cmd)[2] != 0:
            # We should remove the directory that we just created
            if new_package_dir:
                shutil.rmtree(env_directory)

            raise _generic_error(package_name)
    return None


def _execute_command(command):
    """
    :param command: a command to execute
    :type command: list of str
    :returns: stdout, stderr, the process return code
    :rtype: str, str, int
    """

    logger.info('Calling: %r', command)

    process = Subproc().popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE)

    stdout, stderr = process.communicate()

    if process.returncode != 0:
        logger.error("Command script's stdout: %s", stdout)
        logger.error("Command script's stderr: %s", stderr)
    else:
        logger.info("Command script's stdout: %s", stdout)
        logger.info("Command script's stderr: %s", stderr)

    return stdout, stderr, process.returncode


def _generic_error(package_name, err=None):
    """
    :param package: package name
    :type: str
    :param err: error message
    :type err: str
    :returns: generic error when installing package
    :rtype: DCOSException
    """

    msg = 'Error installing {!r} package.'.format(package_name)
    if err:
        msg += ' {}'.format(err)
    return DCOSException(msg)


class InstalledSubcommand(object):
    """ Represents an installed subcommand.

    :param name: The name of the subcommand
    :type name: str
    """

    def __init__(self, name):
        self.name = name

    def _dir(self):
        """
        :returns: path to this subcommand's directory.
        :rtype: str
        """

        return _package_dir(self.name)

    def package_json(self):
        """
        :returns: contents of this subcommand's package.json file.
        :rtype: dict
        """

        package_json_path = os.path.join(self._dir(), 'package.json')
        with util.open_file(package_json_path) as package_json_file:
            return util.load_json(package_json_file)


class SubcommandProcess():

    def __init__(self, executable, command, args):
        """Representes a subcommand running by a forked process

        :param executable: executable to run
        :type executable: executable
        :param command: command to run by executable
        :type command: str
        :param args: arguments for command
        :type args: [str]
        """

        self._executable = executable
        self._command = command
        self._args = args

    def run_and_capture(self):
        """
        Run a command and capture exceptions. This is a blocking call
        :returns: tuple of exitcode, error (or None)
        :rtype: int, str | None
        """

        subproc = Subproc().popen(
            [self._executable,  self._command] + self._args,
            stderr=subprocess.PIPE)

        err = ''
        while subproc.poll() is None:
            line = subproc.stderr.readline().decode('utf-8')
            err += line
            sys.stderr.write(line)
            sys.stderr.flush()

        exitcode = subproc.poll()
        # We only want to catch exceptions, not other stderr messages
        # (such as "task does not exist", so we look for the 'Traceback'
        # string.  This only works for python, so we'll need to revisit
        # this in the future when we support subcommands written in other
        # languages.
        err = ('Traceback' in err and err) or None

        return exitcode, err
