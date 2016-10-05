import os
import time
import json
from dcos import http, util


def fixture_dir():
    """Gets the path to the shakedown dcos fixture directory"""

    return "{}/fixtures".format(os.path.dirname(os.path.realpath(__file__)))


def wait_for_deployment(client, timeout=60):
    """Waits until marathon deployment to finish within the timeout"""

    start = time.time()
    time.sleep(1)
    deployment_count = 1
    while deployment_count > 0:
        time.sleep(1)
        deployments = client.get_deployments()
        deployment_count = len(deployments)
        lapse = time.time()
        if round(lapse - start) > timeout:
            raise Exception("timeout surpassed")
    end = time.time()
    elapse = round(end-start, 3)
    # print("deployment time(s): " + str(elapse))


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
