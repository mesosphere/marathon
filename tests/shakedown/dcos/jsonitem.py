import collections
import json
import re

from dcos import util
from dcos.errors import DCOSException

logger = util.get_logger(__name__)


def parse_json_item(json_item, schema):
    """Parse the json item (optionally based on a schema).

    :param json_item: A JSON item in the form 'key=value'
    :type json_item: str
    :param schema: The JSON schema to use for parsing
    :type schema: dict | None
    :returns: A tuple for the parsed JSON item
    :rtype: (str, any) where any is one of str, int, float, bool, list or dict
    """

    terms = json_item.split('=', 1)
    if len(terms) != 2:
        raise DCOSException('{!r} is not a valid json-item'.format(json_item))

    # Check that it is a valid key in our jsonschema
    key = terms[0]

    # Use the schema if we have it else, guess the type
    if schema:
        value = parse_json_value(key, terms[1], schema)
    else:
        value = _find_type(clean_value(terms[1]))

    return (json.dumps(key), value)


def parse_json_value(key, value, schema):
    """Parse the json value based on a schema.

    :param key: the key property
    :type key: str
    :param value: the value of property
    :type value: str
    :param schema: The JSON schema to use for parsing
    :type schema: dict
    :returns: parsed value
    :rtype: str | int | float | bool | list | dict
    """

    value_type = find_parser(key, schema)
    return value_type(value)


def find_parser(key, schema):
    """
    :param key: JSON field
    :type key: str
    :param schema: The JSON schema to use
    :type schema: dict
    :returns: A callable capable of parsing a string to its type
    :rtype: ValueTypeParser
    """

    key_schema = schema['properties'].get(key)
    if key_schema is None:
        keys = '\n'.join(schema['properties'].keys())
        raise DCOSException(
            'Property {!r} is invalid - '
            'possible properties are: \n{}'.format(key, keys))
    else:
        return ValueTypeParser(key_schema)


class ValueTypeParser(object):
    """Callable for parsing a string against a known JSON type.

    :param schema: The JSON type as a schema
    :type schema: dict
    """

    def __init__(self, schema):
        self.schema = schema

    def __call__(self, value):
        """
        :param value: String to try and parse
        :type value: str
        :returns: The parse value
        :rtype: str | int | float | bool | list | dict
        """

        value = clean_value(value)

        if self.schema['type'] == 'string':
            if self.schema.get('format') == 'uri':
                return _parse_url(value)
            else:
                return _parse_string(value)
        elif self.schema['type'] == 'object':
            return _parse_object(value)
        elif self.schema['type'] == 'number':
            return _parse_number(value)
        elif self.schema['type'] == 'integer':
            return _parse_integer(value)
        elif self.schema['type'] == 'boolean':
            return _parse_boolean(value)
        elif self.schema['type'] == 'array':
            return _parse_array(value)
        else:
            raise DCOSException('Unknown type {!r}'.format(self._value_type))


def clean_value(value):
    """
    :param value: String to try and clean
    :type value: str
    :returns: The cleaned string
    :rtype: str
    """

    if len(value) > 1 and value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    elif len(value) > 1 and value.startswith("'") and value.endswith("'"):
        return value[1:-1]
    else:
        return value


def _find_type(value):
    """Find the correct type of the value

    :param value: value to parse
    :type value: str
    :returns: The parsed value
    :rtype: int|float|
    """
    to_try = [_parse_integer, _parse_number, _parse_boolean, _parse_array,
              _parse_url, _parse_object, _parse_string]
    while len(to_try) > 0:
        try:
            return to_try.pop(0)(value)
        except DCOSException:
            pass

    raise DCOSException(
        'Unable to parse {!r} as a JSON object'.format(value))


def _parse_string(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: str
    """

    return None if value == 'null' else value


def _parse_object(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: dict
    """

    try:
        json_object = json.loads(value)
        if json_object is None or isinstance(json_object, collections.Mapping):
            return json_object
        else:
            raise DCOSException(
                'Unable to parse {!r} as a JSON object'.format(value))
    except ValueError as error:
        logger.exception('Error parsing value as a JSON object')

        msg = 'Unable to parse {!r} as a JSON object: {}'.format(value, error)
        raise DCOSException(msg)


def _parse_number(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: float
    """

    try:
        return None if value == 'null' else float(value)
    except ValueError as error:
        logger.exception('Error parsing value as a JSON number')

        msg = 'Unable to parse {!r} as a float: {}'.format(value, error)
        raise DCOSException(msg)


def _parse_integer(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: int
    """

    try:
        return None if value == 'null' else int(value)
    except ValueError as error:
        logger.exception('Error parsing value as a JSON integer')

        msg = 'Unable to parse {!r} as an int: {}'.format(value, error)
        raise DCOSException(msg)


def _parse_boolean(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: bool
    """

    try:
        boolean = json.loads(value.lower())
        if boolean is None or isinstance(boolean, bool):
            return boolean
        else:
            raise DCOSException(
                'Unable to parse {!r} as a boolean'.format(value))
    except ValueError as error:
        logger.exception('Error parsing value as a JSON boolean')

        msg = 'Unable to parse {!r} as a boolean: {}'.format(value, error)
        raise DCOSException(msg)


def _parse_array(value):
    """
    :param value: The string to parse
    :type value: str
    :returns: The parsed value
    :rtype: list
    """

    try:
        array = json.loads(value)
        if array is None or isinstance(array, collections.Sequence):
            return array
        else:
            raise DCOSException(
                'Unable to parse {!r} as an array'.format(value))
    except ValueError as error:
        logger.exception('Error parsing value as a JSON array')

        msg = 'Unable to parse {!r} as an array: {}'.format(value, error)
        raise DCOSException(msg)


def _parse_url(value):
    """
    :param value: The url to parse
    :type url: str
    :returns: The parsed value
    :rtype: str
    """

    scheme_pattern = r'^(?P<scheme>(?:(?:https?)://))'
    domain_pattern = (
        r'(?P<hostname>(?:(?:[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\.?)+'
        '(?:[A-Z]{2,6}\.?|[A-Z0-9-]{2,}\.?)?|')  # domain,

    value_regex = re.match(
        scheme_pattern +  # http:// or https://
        r'(([^:])+(:[^:]+)?@){0,1}' +  # auth credentials
        domain_pattern +
        r'\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}))'  # or ip
        r'(?P<port>(?::\d+))?'  # port
        r'(?P<path>(?:/?|[/?]\S+))$',  # resource path
        value, re.IGNORECASE)

    if value_regex is None:
        scheme_match = re.match(scheme_pattern, value, re.IGNORECASE)
        if scheme_match is None:
            logger.debug("Defaulting URL to https scheme")
            return "https://" + value
        else:
            raise DCOSException(
                'Unable to parse {!r} as a url'.format(value))
    else:
        return value
