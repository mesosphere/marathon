import os
import subprocess


class Subproc():
    """Represents a wrapper for subprocess
    """

    def __init__(self):
        self._env = os.environ.copy()
        lib_path = 'LD_LIBRARY_PATH'
        lib_path_value = self._env.get(lib_path + '_ORIG')
        # remove pyinstaller overriding `LD_LIBRARY_PATH`
        # remove with stable release of fix:
        # https://github.com/pyinstaller/pyinstaller/pull/2148
        if lib_path_value is not None:
            self._env[lib_path] = lib_path_value
        elif self._env.get(lib_path):
            del self._env[lib_path]

        # Setuptools overrides path to executable from virtualenv,
        # modify this so we can specify a different path
        launcher = '__PYVENV_LAUNCHER__'
        pyvenv_launcher = self._env.get(launcher)
        if pyvenv_launcher is not None:
            del self._env[launcher]

    def check_output(self, args, stdin=None, stderr=None, shell=False):
        """
        call subprocess.check_ouput with modified environment
        """

        return subprocess.check_output(
            args,
            stdin=stdin,
            stderr=stderr,
            shell=shell,
            env=self._env)

    def popen(self, args, stdin=None, stdout=None, stderr=None, shell=False):
        """
        call subprocess.Popen with modified environment
        """

        return subprocess.Popen(
            args,
            stdin=stdin,
            stdout=stdout,
            stderr=stderr,
            shell=shell,
            env=self._env)

    def call(self, args, stdin=None, stdout=None, stderr=None, shell=False):
        """
        call subprocess.call with modified environment
        """

        return subprocess.call(
            args,
            stdin=stdin,
            stdout=stdout,
            stderr=stderr,
            shell=shell,
            env=self._env)
