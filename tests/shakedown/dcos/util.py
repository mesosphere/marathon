import collections
import concurrent.futures
import contextlib
import functools
import hashlib
import json
import logging
import os
import platform
import re
import shutil
import stat
import sys
import tempfile
import time

import jsonschema
import six
from six.moves import urllib

from dcos import constants
from dcos.errors import DCOSException


def get_logger(name):
    """Get a logger

    :param name: The name of the logger. E.g. __name__
    :type name: str
    :returns: The logger for the specified name
    :rtype: logging.Logger
    """

    return logging.getLogger(name)


@contextlib.contextmanager
def tempdir():
    """A context manager for temporary directories.

    The lifetime of the returned temporary directory corresponds to the
    lexical scope of the returned file descriptor.

    :return: Reference to a temporary directory
    :rtype: str
    """

    tmpdir = tempfile.mkdtemp()
    try:
        yield tmpdir
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


@contextlib.contextmanager
def temptext(content=None):
    """A context manager for temporary files.

    The lifetime of the returned temporary file corresponds to the
    lexical scope of the returned file descriptor.

    :param content: Content to populate the file with.
    :type content: bytes
    :return: reference to a temporary file
    :rtype: (fd, str)
    """

    fd, path = tempfile.mkstemp()

    if content:
        os.write(fd, content)

    try:
        yield (fd, path)
    finally:
        # Close the file descriptor and ignore errors
        try:
            os.close(fd)
        except OSError:
            pass

        # delete the path
        shutil.rmtree(path, ignore_errors=True)


@contextlib.contextmanager
def remove_path_on_error(path):
    """A context manager for modifying a specific path
    `path` and all subpaths will be removed on error

    :rtype: None
    """

    try:
        yield path
    except Exception:
        shutil.rmtree(path, ignore_errors=True)
        raise


@contextlib.contextmanager
def silent_output():
    """A context manager for suppressing stdout / stderr, it sets their file
    descriptors to os.devnull and then restores them.

    This is helpful for inhibiting output from subprocesses, as
    replacing sys.stdout and sys.stderr wouldn't be enough.
    """

    # Original fds stdout/stderr point to. Usually 1 and 2 on POSIX systems.
    original_stdout_fd = sys.stdout.fileno()
    original_stderr_fd = sys.stderr.fileno()

    # Save a copy of original outputs fds
    saved_stdout_fd = os.dup(original_stdout_fd)
    saved_stderr_fd = os.dup(original_stderr_fd)

    # A fd for the null device
    devnull = open(os.devnull, 'w')

    def _redirect_outputs(stdout_to_fd, stderr_to_fd):
        """Redirect stdout/stderr to the given file descriptors."""
        # Flush outputs buffers
        sys.stderr.flush()
        sys.stdout.flush()

        # Make original stdout / stderr fds point to the same file as to_fd
        os.dup2(stdout_to_fd, original_stdout_fd)
        os.dup2(stderr_to_fd, original_stderr_fd)

    try:
        # Redirect outputs to os.devnull
        _redirect_outputs(devnull.fileno(), devnull.fileno())
        yield
    finally:
        # redirect outputs back to the original fds
        _redirect_outputs(saved_stdout_fd, saved_stderr_fd)
        devnull.close()


def sh_copy(src, dst):
    """Copy file src to the file or directory dst.

    :param src: source file
    :type src: str
    :param dst: destination file or directory
    :type dst: str
    :rtype: None
    """
    try:
        shutil.copy(src, dst)
    except EnvironmentError as e:
        logger.exception('Unable to copy [%s] to [%s]', src, dst)
        if e.strerror:
            if e.filename:
                raise DCOSException("{}: {}".format(e.strerror, e.filename))
            else:
                raise DCOSException(e.strerror)
        else:
            raise DCOSException(e)
    except Exception as e:
        logger.exception('Unknown error while coping [%s] to [%s]', src, dst)
        raise DCOSException(e)


def sh_move(src, dst):
    """Move file src to the file or directory dst.

    :param src: source file
    :type src: str
    :param dst: destination file or directory
    :type dst: str
    :rtype: None
    """
    try:
        shutil.move(src, dst)
    except EnvironmentError as e:
        logger.exception('Unable to move [%s] to [%s]', src, dst)
        if e.strerror:
            if e.filename:
                raise DCOSException("{}: {}".format(e.strerror, e.filename))
            else:
                raise DCOSException(e.strerror)
        else:
            raise DCOSException(e)
    except Exception as e:
        logger.exception('Unknown error while moving [%s] to [%s]', src, dst)
        raise DCOSException(e)


def ensure_dir_exists(directory):
    """If `directory` does not exist, create it.

    :param directory: path to the directory
    :type directory: string
    :rtype: None
    """

    if not os.path.exists(directory):
        logger.info('Creating directory: %r', directory)

        try:
            os.makedirs(directory, 0o775)
        except os.error as e:
            raise DCOSException(
                'Cannot create directory [{}]: {}'.format(directory, e))


def ensure_file_exists(path):
    """ Create file if it doesn't exist

    :param path: path of file to create
    :type path: str
    :rtype: None
    """

    if not os.path.exists(path):
        try:
            open(path, 'w').close()
            os.chmod(path, 0o600)
        except IOError as e:
            raise DCOSException(
                'Cannot create file [{}]: {}'.format(path, e))


def read_file(path):
    """
    :param path: path to file
    :type path: str
    :returns: contents of file
    :rtype: str
    """
    if not os.path.isfile(path):
        raise DCOSException('path [{}] is not a file'.format(path))

    with open_file(path) as file_:
        return file_.read()


def enforce_file_permissions(path):
    """Enforce 400 or 600 permissions on file

    :param path: Path to the TOML file
    :type path: str
    :rtype: None
    """

    if not os.path.isfile(path):
        raise DCOSException('Path [{}] is not a file'.format(path))

    # Unix permissions are incompatible with windows
    # TODO: https://github.com/dcos/dcos-cli/issues/662
    if sys.platform == 'win32':
        return
    else:
        permissions = oct(stat.S_IMODE(os.stat(path).st_mode))
        if permissions not in ['0o600', '0600', '0o400', '0400']:
            if os.path.realpath(path) != path:
                path = '%s (pointed to by %s)' % (os.path.realpath(path), path)
            msg = (
                "Permissions '{}' for configuration file '{}' are too open. "
                "File must only be accessible by owner. "
                "Aborting...".format(permissions, path))
            raise DCOSException(msg)


def read_file_secure(path):
    """
    Enforce 400 or 600 permissions when reading file

    :param path: path to file
    :type path: str
    :returns: contents of file
    :rtype: str
    """

    enforce_file_permissions(path)

    with open_file(path) as file_:
        return file_.read()


def which(program):
    """Returns the path to the named executable program.

    :param program: The program to locate:
    :type program: str
    :rtype: str
    """

    def is_exe(file_path):
        return os.path.isfile(file_path) and os.access(file_path, os.X_OK)

    file_path, filename = os.path.split(program)
    if file_path:
        if is_exe(program):
            return program
    elif constants.PATH_ENV in os.environ:
        for path in os.environ[constants.PATH_ENV].split(os.pathsep):
            path = path.strip('"')
            exe_file = os.path.join(path, program)
            if is_exe(exe_file):
                return exe_file

    if is_windows_platform() and not program.endswith('.exe'):
        return which(program + '.exe')

    return None


def dcos_bin_path():
    """Returns the real DCOS path based on the current executable

    :returns: the real path to the DCOS path
    :rtype: str
    """

    return os.path.dirname(os.path.realpath(sys.argv[0]))


def configure_process_from_environ():
    """Configure the program's logger and debug messages using the environment
    variable

    :rtype: None
    """

    configure_logger(os.environ.get(constants.DCOS_LOG_LEVEL_ENV))
    configure_debug(os.environ.get(constants.DCOS_DEBUG_ENV))


def configure_debug(is_debug):
    """Configure debug messages for the program

    :param is_debug: Enable debug message if true; otherwise disable debug
                     messages
    :type is_debug: bool
    :rtype: None
    """

    if is_debug:
        six.moves.http_client.HTTPConnection.debuglevel = 1


def configure_logger(log_level):
    """Configure the program's logger.

    :param log_level: Log level for configuring logging
    :type log_level: str
    :rtype: None
    """

    if log_level is None:
        logging.disable(logging.CRITICAL)
        return None

    if log_level in constants.VALID_LOG_LEVEL_VALUES:
        logging.basicConfig(
            format=('%(threadName)s: '
                    '%(asctime)s '
                    '%(pathname)s:%(funcName)s:%(lineno)d - '
                    '%(message)s'),
            stream=sys.stderr,
            level=log_level.upper())
        return None

    msg = 'Log level set to an unknown value {!r}. Valid values are {!r}'
    raise DCOSException(
        msg.format(log_level, constants.VALID_LOG_LEVEL_VALUES))


def load_json(reader, keep_order=False):
    """Deserialize a reader into a python object

    :param reader: the json reader
    :type reader: a :code:`.read()`-supporting object
    :param keep_order: whether the return should be an ordered dictionary
    :type keep_order: bool
    :returns: the deserialized JSON object
    :rtype: dict | list | str | int | float | bool
    """

    try:
        if keep_order:
            return json.load(reader, object_pairs_hook=collections.OrderedDict)
        else:
            return json.load(reader)
    except Exception as error:
        logger.error(
            'Unhandled exception while loading JSON: %r',
            error)

        raise DCOSException('Error loading JSON: {}'.format(error))


def load_jsons(value):
    """Deserialize a string to a python object

    :param value: The JSON string
    :type value: str
    :returns: The deserialized JSON object
    :rtype: dict | list | str | int | float | bool
    """

    try:
        return json.loads(value)
    except Exception:
        logger.exception(
            'Unhandled exception while loading JSON: %r',
            value)

        raise DCOSException('Error loading JSON.')


def validate_json(instance, schema):
    """Validate an instance under the given schema.

    :param instance: the instance to validate
    :type instance: dict
    :param schema: the schema to validate with
    :type schema: dict
    :returns: list of errors as strings
    :rtype: [str]
    """

    def sort_key(ve):
        return six.u(_hack_error_message_fix(ve.message))

    validator = jsonschema.Draft4Validator(schema)
    validation_errors = list(validator.iter_errors(instance))
    validation_errors = sorted(validation_errors, key=sort_key)

    return [_format_validation_error(e) for e in validation_errors]


# TODO(jsancio): clean up this hack
# The error string from jsonschema already contains improperly formatted
# JSON values, so we have to resort to removing the unicode prefix using
# a regular expression.
def _hack_error_message_fix(message):
    """
    :param message: message to fix by removing u'...'
    :type message: str
    :returns: the cleaned up message
    :rtype: str
    """

    # This regular expression matches the character 'u' followed by the
    # single-quote character, all optionally preceded by a left square
    # bracket, parenthesis, curly brace, or whitespace character.
    return re.compile("([\[\(\{\s])u'").sub(
        "\g<1>'",
        re.compile("^u'").sub("'", message))


def _format_validation_error(error):
    """
    :param error: validation error to format
    :type error: jsonchema.exceptions.ValidationError
    :returns: string representation of the validation error
    :rtype: str
    """

    error_message = _hack_error_message_fix(error.message)

    match = re.search("(.+) is a required property", error_message)
    if match:
        message = 'Error: missing required property {}.'.format(
            match.group(1))
    else:
        message = 'Error: {}\n'.format(error_message)
        if len(error.absolute_path) > 0:
            message += 'Path: {}\n'.format(
                       '.'.join(
                           [six.text_type(path)
                            for path in error.absolute_path]))
        message += 'Value: {}'.format(json.dumps(error.instance))

    return message


def create_schema(obj, add_properties=False):
    """ Creates a basic json schema derived from `obj`.

    :param obj: object for which to derive a schema
    :type obj: str | int | float | dict | list
    :param add_properties: whether to allow additional properties
    :type add_properties: bool
    :returns: json schema
    :rtype: dict
    """

    if isinstance(obj, bool):
        return {'type': 'boolean'}

    elif isinstance(obj, float):
        return {'type': 'number'}

    elif isinstance(obj, six.integer_types):
        return {'type': 'integer'}

    elif isinstance(obj, six.string_types):
        return {'type': 'string'}

    elif isinstance(obj, collections.Mapping):
        schema = {'type': 'object',
                  'properties': {},
                  'additionalProperties': add_properties,
                  'required': list(obj.keys())}

        for key, val in obj.items():
            schema['properties'][key] = create_schema(val, add_properties)

        return schema

    elif isinstance(obj, collections.Sequence):
        schema = {'type': 'array'}
        if obj:
            schema['items'] = create_schema(obj[0], add_properties)
        return schema

    else:
        raise ValueError(
            'Cannot create schema with object {} of unrecognized type'
            .format(six.text_type(obj)))


def list_to_err(errs):
    """convert list of error strings to a single string

    :param errs: list of string errors
    :type errs: [str]
    :returns: error message
    :rtype: str
    """

    return str.join('\n\n', errs)


def parse_int(string):
    """Parse string and an integer

    :param string: string to parse as an integer
    :type string: str
    :returns: the interger value of the string
    :rtype: int
    """

    try:
        return int(string)
    except ValueError:
        logger.error(
            'Unhandled exception while parsing string as int: %r',
            string)

        raise DCOSException('Error parsing string as int')


def parse_float(string):
    """Parse string and an float

    :param string: string to parse as an float
    :type string: str
    :returns: the float value of the string
    :rtype: float
    """

    try:
        return float(string)
    except ValueError:
        logger.error(
            'Unhandled exception while parsing string as float: %r',
            string)

        raise DCOSException('Error parsing string as float')


def is_windows_platform():
    """
    :returns: True is program is running on Windows platform, False
     in other case
    :rtype: boolean
    """

    return platform.system() == "Windows"


def duration(fn):
    """ Decorator to log the duration of a function.

    :param fn: function to measure
    :type fn: function
    :returns: wrapper function
    :rtype: function
    """

    @functools.wraps(fn)
    def timer(*args, **kwargs):
        start = time.time()
        try:
            return fn(*args, **kwargs)
        finally:
            logger.debug("duration: {0}.{1}: {2:2.2f}s".format(
                fn.__module__,
                fn.__name__,
                time.time() - start))

    return timer


def humanize_bytes(b):
    """ Return a human representation of a number of bytes.

    :param b: number of bytes
    :type b: number
    :returns: human representation of a number of bytes
    :rtype: str
    """

    abbrevs = (
        (1 << 30, 'GB'),
        (1 << 20, 'MB'),
        (1 << 10, 'kB'),
        (1, 'B')
    )
    for factor, suffix in abbrevs:
        if b >= factor:
            break

    return "{0:.2f} {1}".format(b/float(factor), suffix)


@contextlib.contextmanager
def open_file(path,  *args):
    """Context manager that opens a file, and raises a DCOSException if
    it fails.

    :param path: file path
    :type path: str
    :param *args: other arguments to pass to `open`
    :type *args: [str]
    :returns: a context manager
    :rtype: context manager
    """

    try:
        file_ = open(path, *args)
        yield file_
    except IOError as e:
        logger.exception('Unable to open file: %s', path)

        raise io_exception(path, e.errno)

    file_.close()


def io_exception(path, errno):
    """Returns a DCOSException for when there is an error opening the
    file at `path`

    :param path: file path
    :type path: str
    :param errno: IO error number
    :type errno: int
    :returns: DCOSException
    :rtype: DCOSException
    """

    return DCOSException('Error opening file [{}]: {}'.format(
        path, os.strerror(errno)))


STREAM_CONCURRENCY = 20


def stream(fn, objs):
    """Apply `fn` to `objs` in parallel, yielding the (Future, obj) for
    each as it completes.

    :param fn: function
    :type fn: function
    :param objs: objs
    :type objs: objs
    :returns: iterator over (Future, typeof(obj))
    :rtype: iterator over (Future, typeof(obj))

    """

    with concurrent.futures.ThreadPoolExecutor(STREAM_CONCURRENCY) as pool:
        jobs = {pool.submit(fn, obj): obj for obj in objs}
        for job in concurrent.futures.as_completed(jobs):
            yield job, jobs[job]


def get_ssh_options(config_file, options):
    """Returns the SSH arguments for the given parameters.  Used by
    commands that wrap SSH.

    :param config_file: SSH config file.
    :type config_file: str | None
    :param options: SSH options
    :type options: [str]
    :rtype: str
    """

    ssh_options = ' '.join('-o {}'.format(opt) for opt in options)

    if config_file:
        ssh_options += ' -F {}'.format(config_file)

    if ssh_options:
        ssh_options += ' '

    return ssh_options


def normalize_marathon_id_path(id_path):
    """Normalizes a Marathon "ID path", such as an app ID, group ID, or pod ID.

    A normalized path has a single leading forward slash (/), no trailing
    forward slashes, and has all URL-unsafe characters escaped, as if by
    urllib.parse.quote().

    :param id_path
    :type id_path: str
    :returns: normalized path
    :rtype: str
    """

    return urllib.parse.quote('/' + id_path.strip('/'))


logger = get_logger(__name__)


def md5_hash_file(file):
    """Calculates the md5 of a file. Will set the
    file pointer to beginning of the file after being
    called.

   :param file: file to hash, file pointer
    must be at the beginning of the file.
   :type file: file
   :returns: digest in hexadecimal
   :rtype: str
   """
    hasher = hashlib.md5()
    for chunk in iter(lambda: file.read(4096), b''):
        hasher.update(chunk)
    file.seek(0)
    return hasher.hexdigest()


def read_file_json(path):
    """ Read the options at the given file path.

    :param path: file path
    :type path: None | str
    :returns: options
    :rtype: dict
    """
    if path is None:
        return {}
    else:
        # Expand ~ in the path
        path = os.path.expanduser(path)
        with open_file(path) as options_file:
            return load_json(options_file)
