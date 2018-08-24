import collections
import copy
import json
import os

import pkg_resources
import toml

from dcos import constants, jsonitem, util
from dcos.errors import DCOSException

logger = util.get_logger(__name__)


def uses_deprecated_config():
    """Returns True if the configuration for the user's CLI
    is the deprecated 'global' config instead of the cluster
    specific config
    """

    global_config = get_global_config_path()
    cluster_config = get_clusters_path()
    return not os.path.exists(cluster_config) and os.path.exists(global_config)


def get_global_config_path():
    """Returns the path to the deprecated global DCOS config file.

    :returns: path to the DCOS config file
    :rtype: str
    """

    default_path = os.path.join(get_config_dir_path(), "dcos.toml")
    return os.environ.get(constants.DCOS_CONFIG_ENV, default_path)


def get_global_config(mutable=False):
    """Returns the deprecated global DCOS config file

    :param mutable: True if the returned Toml object should be mutable
    :type mutable: boolean
    :returns: Configuration object
    :rtype: Toml | MutableToml
    """

    return load_from_path(get_global_config_path(), mutable)


def get_attached_cluster_path():
    """
    The attached cluster is denoted by a file named "attached" in one of the
    cluster directories. Ex: $DCOS_DIR/clusters/CLUSTER_ID/attached

    :returns: path to the director of the attached cluster
    :rtype: str | None
    """

    path = get_clusters_path()
    if not os.path.exists(path):
        return None

    attached = None
    clusters = os.listdir(get_clusters_path())
    cluster_envvar = os.environ.get(constants.DCOS_CLUSTER)

    for c in clusters:
        cluster_path = os.path.join(path, c)
        name = get_config_val("cluster.name",
                              load_from_path(
                                  os.path.join(cluster_path, "dcos.toml")))
        if cluster_envvar is not None \
           and (cluster_envvar == c or cluster_envvar == name):
            return cluster_path
        if os.path.exists(os.path.join(
                cluster_path, constants.DCOS_CLUSTER_ATTACHED_FILE)):
            attached = cluster_path

    if attached is not None:
        return attached

    # if only one cluster, set as attached
    if len(clusters) == 1:
        cluster_path = os.path.join(path, clusters[0])
        util.ensure_file_exists(os.path.join(
            cluster_path, constants.DCOS_CLUSTER_ATTACHED_FILE))
        return cluster_path

    return None


def get_clusters_path():
    """
    :returns: path to the directory of cluster configs
    :rtype: str
    """

    return os.path.join(get_config_dir_path(), constants.DCOS_CLUSTERS_SUBDIR)


def get_config_path():
    """Returns the path to the DCOS config file of the attached cluster.
    If still using "global" config return that toml instead

    :returns: path to the DCOS config file
    :rtype: str
    """

    if uses_deprecated_config():
        return get_global_config_path()
    else:
        cluster_path = get_attached_cluster_path()
        return os.path.join(cluster_path, "dcos.toml")


def get_config_dir_path():
    """
    :returns: path to the DC/OS data directory
    :rtype: str
    """

    config_dir = os.environ.get(constants.DCOS_DIR_ENV) or \
        os.path.join("~", constants.DCOS_DIR)
    return os.path.expanduser(config_dir)


def get_config(mutable=False):
    """Returns the DCOS configuration object and creates config file is None
    found and `DCOS_CONFIG` set to default value. Only use to get the config,
    not to resolve a specific config parameter. This should be done with
    `get_config_val`.

    :param mutable: True if the returned Toml object should be mutable
    :type mutable: boolean
    :returns: Configuration object
    :rtype: Toml | MutableToml
    """

    cluster_path = get_attached_cluster_path()
    if cluster_path is None:
        return get_global_config(mutable)

    util.ensure_dir_exists(os.path.dirname(cluster_path))

    path = os.path.join(cluster_path, "dcos.toml")
    return load_from_path(path, mutable)


def get_config_val_envvar(name, config=None):
    """Returns a tuple of the config value for the specified key and
    the name of any environment variable which overwrote the value
    from the configuration.  If no environment variable applies, None
    is returned for the environment variable name.
    Looks for corresponding environment variable first, and if it
    doesn't exist, uses the config value.
    - "core" properties get resolved to env variable DCOS_SUBKEY. With the
    exception of subkeys that already start with DCOS, in which case we look
    for SUBKEY first, and "DCOS_SUBKEY" second, and finally the config value.
    - everything else gets resolved to DCOS_SECTION_SUBKEY

    :param name: name of paramater
    :type name: str
    :param config: config
    :type config: Toml
    :returns: value of 'name' parameter
    :rtype: (str | None, str | None)
    """

    if config is None:
        config = get_config()

    section, subkey = split_key(name.upper())

    env_var = None
    if section == "CORE":
        if subkey.startswith("DCOS") and os.environ.get(subkey):
            env_var = subkey
        else:
            env_var = "DCOS_{}".format(subkey)
    else:
        env_var = "DCOS_{}_{}".format(section, subkey)

    return os.environ.get(env_var) or config.get(name), env_var or None


def get_config_val(name, config=None):
    """Returns the config value for the specified key. Looks for corresponding
    environment variable first, and if it doesn't exist, uses the config value.
    - "core" properties get resolved to env variable DCOS_SUBKEY. With the
    exception of subkeys that already start with DCOS, in which case we look
    for SUBKEY first, and "DCOS_SUBKEY" second, and finally the config value.
    - everything else gets resolved to DCOS_SECTION_SUBKEY

    :param name: name of paramater
    :type name: str
    :param config: config
    :type config: Toml
    :returns: value of 'name' parameter
    :rtype: str | None
    """

    val, _ = get_config_val_envvar(name, config)
    return val


def missing_config_exception(keys):
    """ DCOSException for a missing config value

    :param keys: keys in the config dict
    :type keys: [str]
    :returns: DCOSException
    :rtype: DCOSException
    """

    msg = '\n'.join(
        'Missing required config parameter: "{0}".'.format(key) +
        '  Please run `dcos config set {0} <value>`.'.format(key)
        for key in keys)
    return DCOSException(msg)


def set_val(name, value, config_path=None):
    """
    :param name: name of paramater
    :type name: str
    :param value: value to set to paramater `name`
    :type param: str
    :param config_path: path to config to use
    :type config_path: str
    :returns: Toml config, message of change
    :rtype: Toml, str
    """

    if config_path:
        toml_config = load_from_path(config_path, True)
    else:
        toml_config = get_config(True)

    section, subkey = split_key(name)

    config_schema = get_config_schema(section)

    new_value = jsonitem.parse_json_value(subkey, value, config_schema)

    toml_config_pre = copy.deepcopy(toml_config)
    if section not in toml_config_pre._dictionary:
        toml_config_pre._dictionary[section] = {}

    value_exists = name in toml_config
    old_value = toml_config.get(name)

    # remove token when core.dcos_url is changed
    token_unset = False
    if value_exists and old_value != new_value and name == "core.dcos_url":
        token_unset = bool(toml_config.pop("core.dcos_acs_token", False))

    toml_config[name] = new_value

    check_config(toml_config_pre, toml_config, section)

    save(toml_config, config_path)

    msg = "[{}]: ".format(name)
    if name == "core.dcos_acs_token":
        if not value_exists:
            msg += "set"
        elif old_value == new_value:
            msg += "already set to that value"
        else:
            msg += "changed"
    elif not value_exists:
        msg += "set to '{}'".format(new_value)
    elif old_value == new_value:
        msg += "already set to '{}'".format(old_value)
    else:
        msg += "changed from '{}' to '{}'".format(old_value, new_value)

    if token_unset:
        msg += "\n[core.dcos_acs_token]: removed"

    return toml_config, msg


def load_from_path(path, mutable=False):
    """Loads a TOML file from the path

    :param path: Path to the TOML file
    :type path: str
    :param mutable: True if the returned Toml object should be mutable
    :type mutable: boolean
    :returns: Map for the configuration file
    :rtype: Toml | MutableToml
    """

    util.ensure_dir_exists(os.path.dirname(path))
    util.ensure_file_exists(path)
    util.enforce_file_permissions(path)
    with util.open_file(path, 'r') as config_file:
        try:
            toml_obj = toml.loads(config_file.read())
        except Exception as e:
            raise DCOSException(
                'Error parsing config file at [{}]: {}'.format(path, e))
        return (MutableToml if mutable else Toml)(toml_obj)


def save(toml_config, config_path=None):
    """
    :param toml_config: TOML configuration object
    :type toml_config: MutableToml or Toml
    :param config_path: path to config to use
    :type config_path: str
    """

    serial = toml.dumps(toml_config._dictionary)
    if config_path is None:
        config_path = get_config_path()

    util.ensure_file_exists(config_path)
    util.enforce_file_permissions(config_path)
    with util.open_file(config_path, 'w') as config_file:
        config_file.write(serial)


def _get_path(toml_config, path):
    """
    :param config: Dict with the configuration values
    :type config: dict
    :param path: Path to the value. E.g. 'path.to.value'
    :type path: str
    :returns: Value stored at the given path
    :rtype: double, int, str, list or dict
    """

    for section in path.split('.'):
        toml_config = toml_config[section]

    return toml_config


def unset(name):
    """
    :param name: name of config value to unset
    :type name: str
    :returns: message of property removed
    :rtype: str
    """

    toml_config = get_config(True)
    toml_config_pre = copy.deepcopy(toml_config)
    section = name.split(".", 1)[0]
    if section not in toml_config_pre._dictionary:
        toml_config_pre._dictionary[section] = {}
    value = toml_config.pop(name, None)

    if value is None:
        raise DCOSException("Property {!r} doesn't exist".format(name))
    elif isinstance(value, collections.Mapping):
        raise DCOSException(_generate_choice_msg(name, value))
    else:
        msg = "Removed [{}]".format(name)
        # dcos_acs_token is coupled to a specific dcos_url
        if name == "core.dcos_url":
            unset_token = bool(toml_config.pop("core.dcos_acs_token", None))
            if unset_token:
                msg += " and [core.dcos_acs_token]"
        save(toml_config)
        return msg


def _generate_choice_msg(name, value):
    """
    :param name: name of the property
    :type name: str
    :param value: dictionary for the value
    :type value: dcos.config.Toml
    :returns: an error message for top level properties
    :rtype: str
    """

    message = ("Property {!r} doesn't fully specify a value - "
               "possible properties are:").format(name)
    for key, _ in sorted(value.property_items()):
        message += '\n{}.{}'.format(name, key)

    return message


def _iterator(parent, dictionary):
    """
    :param parent: Path to the value parameter
    :type parent: str
    :param dictionary: Value of the key
    :type dictionary: collection.Mapping
    :returns: An iterator of tuples for each property and value
    :rtype: iterator of (str, any) where any can be str, int, double, list
    """

    for key, value in dictionary.items():

        new_key = key
        if parent is not None:
            new_key = "{}.{}".format(parent, key)

        if not isinstance(value, collections.Mapping):
            yield (new_key, value)
        else:
            for x in _iterator(new_key, value):
                yield x


def split_key(name):
    """
    :param name: the full property path - e.g. marathon.url
    :type name: str
    :returns: the section and property name
    :rtype: (str, str)
    """

    terms = name.split('.', 1)
    if len(terms) != 2:
        raise DCOSException('Property name must have both a section and '
                            'key: <section>.<key> - E.g. marathon.url')

    return (terms[0], terms[1])


def get_config_schema(command):
    """
    :param command: the subcommand name
    :type command: str
    :returns: the subcommand's configuration schema
    :rtype: dict
    """

    # import here to avoid circular import
    from dcos.subcommand import (
            command_executables, config_schema, default_subcommands)

    # handle config schema for core.* properties and built-in subcommands
    if command == "core" or command in default_subcommands():
        try:
            schema = pkg_resources.resource_string(
                    'dcos', 'data/config-schema/{}.json'.format(command))
        except FileNotFoundError:
            msg = "Subcommand '{}' is not configurable.".format(command)
            raise DCOSException(msg)

        return json.loads(schema.decode('utf-8'))

    try:
        executable = command_executables(command)
    except DCOSException as e:
        msg = "Config section '{}' is invalid: {}".format(command, e)
        raise DCOSException(msg)

    return config_schema(executable, command)


def get_property_description(section, subkey):
    """
    :param section: section of config paramater
    :type section: str
    :param subkey: property within 'section'
    :type subkey: str
    :returns: description of section.subkey or None if no description
    :rtype: str | None
    """

    schema = get_config_schema(section)
    property_info = schema["properties"].get(subkey)
    if property_info is not None:
        return property_info.get("description")
    else:
        raise DCOSException(
            "No schema found found for {}.{}".format(section, subkey))


def check_config(toml_config_pre, toml_config_post, section):
    """
    :param toml_config_pre: dictionary for the value before change
    :type toml_config_pre: dcos.api.config.Toml
    :param toml_config_post: dictionary for the value with change
    :type toml_config_post: dcos.api.config.Toml
    :param section: section of the config to check
    :type section: str
    :returns: process status
    :rtype: int
    """

    errors_pre = util.validate_json(toml_config_pre._dictionary[section],
                                    get_config_schema(section))
    errors_post = util.validate_json(toml_config_post._dictionary[section],
                                     get_config_schema(section))

    logger.info('Comparing changes in the configuration...')
    logger.info('Errors before the config command: %r', errors_pre)
    logger.info('Errors after the config command: %r', errors_post)

    if len(errors_post) != 0:
        if len(errors_pre) == 0:
            raise DCOSException(util.list_to_err(errors_post))

        def _errs(errs):
            return set([e.split('\n')[0] for e in errs])

        diff_errors = _errs(errors_post) - _errs(errors_pre)
        if len(diff_errors) != 0:
            raise DCOSException(util.list_to_err(errors_post))


def generate_choice_msg(name, value):
    """
    :param name: name of the property
    :type name: str
    :param value: dictionary for the value
    :type value: dcos.config.Toml
    :returns: an error message for top level properties
    :rtype: str
    """

    message = ("Property {!r} doesn't fully specify a value - "
               "possible properties are:").format(name)
    for key, _ in sorted(value.property_items()):
        message += '\n{}.{}'.format(name, key)

    return message


def generate_root_schema(toml_config):
    """
    :param toml_configs: dictionary of values
    :type toml_config: TomlConfig
    :returns: configuration_schema
    :rtype: jsonschema
    """

    root_schema = {
        '$schema': 'http://json-schema.org/schema#',
        'type': 'object',
        'properties': {},
        'additionalProperties': False,
    }

    # Load the config schema from all the subsections into the root schema
    for section in toml_config.keys():
        config_schema = get_config_schema(section)
        root_schema['properties'][section] = config_schema

    return root_schema


class Toml(collections.Mapping):
    """Class for getting value from TOML.

    :param dictionary: configuration dictionary
    :type dictionary: dict
    """

    def __init__(self, dictionary):
        self._dictionary = dictionary

    def __getitem__(self, path):
        """
        :param path: Path to the value. E.g. 'path.to.value'
        :type path: str
        :returns: Value stored at the given path
        :rtype: double, int, str, list or dict
        """

        toml_config = _get_path(self._dictionary, path)
        if isinstance(toml_config, collections.Mapping):
            return Toml(toml_config)
        else:
            return toml_config

    def __iter__(self):
        """
        :returns: Dictionary iterator
        :rtype: iterator
        """

        return iter(self._dictionary)

    def property_items(self):
        """Iterator for full-path keys and values

        :returns: Iterator for pull-path keys and values
        :rtype: iterator of tuples
        """

        return _iterator(None, self._dictionary)

    def __len__(self):
        """
        :returns: The length of the dictionary
        :rtype: int
        """

        return len(self._dictionary)


class MutableToml(collections.MutableMapping):
    """Class for managing CLI configuration through TOML.

    :param dictionary: configuration dictionary
    :type dictionary: dict
    """

    def __init__(self, dictionary):
        self._dictionary = dictionary

    def __getitem__(self, path):
        """
        :param path: Path to the value. E.g. 'path.to.value'
        :type path: str
        :returns: Value stored at the given path
        :rtype: double, int, str, list or dict
        """

        toml_config = _get_path(self._dictionary, path)
        if isinstance(toml_config, collections.MutableMapping):
            return MutableToml(toml_config)
        else:
            return toml_config

    def __iter__(self):
        """
        :returns: Dictionary iterator
        :rtype: iterator
        """

        return iter(self._dictionary)

    def property_items(self):
        """Iterator for full-path keys and values

        :returns: Iterator for pull-path keys and values
        :rtype: iterator of tuples
        """

        return _iterator(None, self._dictionary)

    def __len__(self):
        """
        :returns: The length of the dictionary
        :rtype: int
        """

        return len(self._dictionary)

    def __setitem__(self, path, value):
        """
        :param path: Path to set
        :type path: str
        :param value: Value to store
        :type value: double, int, str, list or dict
        """

        toml_config = self._dictionary

        sections = path.split('.')
        for section in sections[:-1]:
            toml_config = toml_config.setdefault(section, {})

        toml_config[sections[-1]] = value

    def __delitem__(self, path):
        """
        :param path: Path to delete
        :type path: str
        """
        toml_config = self._dictionary

        sections = path.split('.')
        for section in sections[:-1]:
            toml_config = toml_config[section]

        del toml_config[sections[-1]]
