import os
from dcos import http, util

def fixture_dir():
    """Gets the path to the shakedown dcos fixture directory"""

    return "{}/fixtures".format(os.path.dirname(os.path.realpath(__file__)))


# should be in shakedown
def get_resource(resource):
    """
    :param resource: optional filename or http(s) url
    for the application or group resource
    :type resource: str
    :returns: resource
    :rtype: dict
    """
    if resource is not None:
        if os.path.isfile(resource):
            with util.open_file(resource) as resource_file:
                return util.load_json(resource_file)
        else:
            try:
                http.silence_requests_warnings()
                req = http.get(resource)
                if req.status_code == 200:
                    data = b''
                    for chunk in req.iter_content(1024):
                        data += chunk
                    return util.load_jsons(data.decode('utf-8'))
                else:
                    raise Exception
            except Exception:
                logger.exception('Cannot read from resource %s', resource)
                raise DCOSException(
                    "Can't read from resource: {0}.\n"
                    "Please check that it exists.".format(resource))
