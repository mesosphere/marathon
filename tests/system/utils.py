import os
import retrying
import uuid

from dcos import http, util
from dcos.errors import DCOSException
import shakedown
import common


def make_id(prefix=None):
    random_part = uuid.uuid4().hex
    if prefix is None:
        return random_part
    return '/{}-{}'.format(prefix, random_part)


# should be in shakedown
def get_resource(resource):
    """:param resource: optional filename or http(s) url for the application or group resource
       :type resource: str
       :returns: resource
       :rtype: dict
    """

    if resource is None:
        return None

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
            raise DCOSException("Can't read from resource: {0}. Please check that it exists.".format(resource))


@retrying.retry(wait_fixed=1000, stop_max_attempt_number=30, retry_on_exception=common.ignore_exception)
def marathon_leadership_changed(original_leader):
    current_leader = shakedown.marathon_leader_ip()
    print('leader: {}'.format(current_leader))
    assert original_leader != current_leader
