import json

from six.moves import urllib

from dcos import config, cosmos, http, packagemanager, rpcclient, util
from dcos.errors import DCOSException

logger = util.get_logger(__name__)

EMBED_ACTIVE_RUNS = 'activeRuns'
EMBED_SCHEDULES = 'schedules'
EMBED_HISTORY = 'history'
EMBED_HISTORY_SUMMARY = 'historySummary'


def create_client(toml_config=None):
    """Creates a Metronome client with the supplied configuration.

    :param toml_config: configuration dictionary
    :type toml_config: config.Toml
    :returns: Metronome client
    :rtype: dcos.metronome.Client
    """

    if toml_config is None:
        toml_config = config.get_config()

    metronome_url = _get_metronome_url(toml_config)
    timeout = config.get_config_val('core.timeout') or http.DEFAULT_TIMEOUT
    rpc_client = rpcclient.create_client(metronome_url, timeout)

    logger.info('Creating metronome client with: %r', metronome_url)
    return Client(rpc_client)


def _get_embed_query_string(embed_list):
    return '?{}'.format('&'.join('embed=%s' % (item) for item in embed_list))


def _get_metronome_url(toml_config=None):
    """
    :param toml_config: configuration dictionary
    :type toml_config: config.Toml
    :returns: metronome base url
    :rtype: str
    """
    if toml_config is None:
        toml_config = config.get_config()

    metronome_url = config.get_config_val('metronome.url', toml_config)
    if metronome_url is None:
        # dcos must be capable to use dcos_url
        _check_capability()
        dcos_url = config.get_config_val('core.dcos_url', toml_config)
        if dcos_url is None:
            raise config.missing_config_exception(['core.dcos_url'])
        metronome_url = urllib.parse.urljoin(dcos_url, 'service/metronome/')

    return metronome_url


class Client(object):
    """Class for talking to the Metronome server.

    :param rpc_client: provides a method for making HTTP requests
    :type rpc_client: _RpcClient
    """

    def __init__(self, rpc_client):
        self._rpc = rpc_client

    def get_about(self):
        """Returns info about Metronome instance

        :returns Metronome information
        :rtype: dict
        """

        response = self._rpc.http_req(http.get, 'v1/info')

        return response.json()

    def get_job(self, job_id, embed_with=None):
        """Returns a representation of the requested job.

        :param job_id: the ID of the application
        :type job_id: str
        :param embed_with: list of strings to ?embed=str&embed=str2...
        :type embed_with: [str]
        :returns: the requested Metronome job
        :rtype: dict
        """
        # refactor util name it isn't marathon specific
        job_id = util.normalize_marathon_id_path(job_id)
        embeds = _get_embed_query_string(embed_with) if embed_with else None
        url = ('v1/jobs{}{}'.format(job_id, embeds)
               if embeds else 'v1/jobs{}'.format(job_id))
        response = self._rpc.http_req(http.get, url)
        return response.json()

    def get_jobs(self, embed_with=None):
        """Get a list of known jobs.

        :param embed_with: list of strings to ?embed=str&embed=str2...
        :type embed_with: [str]
        :returns: list of known jobs
        :rtype: [dict]
        """
        embeds = _get_embed_query_string(embed_with) if embed_with else None
        url = 'v1/jobs{}'.format(embeds) if embeds else 'v1/jobs'
        response = self._rpc.http_req(http.get, url)
        return response.json()

    def add_job(self, job_resource):
        """Add a new job.

        :param job_resource: job resource
        :type job_resource: dict, bytes or file
        :returns: the job description
        :rtype: dict
        """

        # The file type exists only in Python 2, preventing type(...) is file.
        if hasattr(job_resource, 'read'):
            job_json = json.load(job_resource)
        else:
            job_json = job_resource

        response = self._rpc.http_req(http.post, 'v1/jobs', json=job_json)
        # need deployment ID
        return response.json()

    def _update_req(
            self, resource_type, resource_id, resource_json, force=False):
        """Send an HTTP request to update an application, group, or pod.

        :param resource_type: one of 'apps', 'groups', or 'pods'
        :type resource_type: str
        :param resource_id: the app, group, or pod ID
        :type resource_id: str
        :param resource_json: the json payload
        :type resource_json: {}
        :param force: whether to override running deployments
        :type force: bool
        :returns: the response from Metronome
        :rtype: requests.Response
        """

        path_template = 'v1/{}/{{}}'.format(resource_type)
        path = self._job_id_path_format(path_template, resource_id)
        params = self._force_params(force)
        return self._rpc.http_req(
            http.put, path, params=params, json=resource_json)

    def _update(self, resource_type, resource_id, resource_json, force=False):
        """Update an application or group.

        The HTTP response is handled differently for pods; see `update_pod`.

        :param resource_type: either 'apps' or 'groups'
        :type resource_type: str
        :param resource_id: the app or group ID
        :type resource_id: str
        :param resource_json: the json payload
        :type resource_json: {}
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: str
        """

        response = self._update_req(
            resource_type, resource_id, resource_json, force)
        body_json = self._parse_json(response)

        try:
            return body_json.get('deploymentId')
        except KeyError:
            template = ('Error: missing "deploymentId" field in the following '
                        'JSON response from Job:\n{}')
            rendered_json = json.dumps(body_json, indent=2, sort_keys=True)
            raise DCOSException(template.format(rendered_json))

    def update_job(self, job_id, payload, force=False):
        """Update a job.

        :param job_id: the job id
        :type job_id: str
        :param payload: the json payload
        :type payload: dict
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: str
        """

        return self._update('jobs', job_id, payload, force)

    def remove_job(self, job_id, force=False):
        """Completely removes the requested application.

        :param job_id: the ID of the job to remove
        :type job_id: str
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        job_id = util.normalize_marathon_id_path(job_id)
        params = self._force_params(force)
        path = 'v1/jobs{}'.format(job_id)
        self._rpc.http_req(http.delete, path, params=params)

    def get_schedules(self, job_id):
        """Gets the schedules for a given job

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: json of schedules
        """
        job_id = util.normalize_marathon_id_path(job_id)
        path = 'v1/jobs{}/schedules'.format(job_id)
        response = self._rpc.http_req(http.get, path)
        return response.json()

    def get_schedule(self, job_id, schedule_id):
        """Gets the schedules for a given job

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: json of schedules
        """
        job_id = util.normalize_marathon_id_path(job_id)
        schedule_id = util.normalize_marathon_id_path(schedule_id)
        path = 'v1/jobs{}/schedules/{}'.format(job_id, schedule_id)
        response = self._rpc.http_req(http.get, path)
        return response.json()

    def add_schedule(self, job_id, schedule_resource):
        """Gets the schedules for a given job

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: json of schedules
        """
        job_id = util.normalize_marathon_id_path(job_id)
        if hasattr(schedule_resource, 'read'):
            schedule_json = json.load(schedule_resource)
        else:
            schedule_json = schedule_resource

        path = 'v1/jobs{}/schedules'.format(job_id)
        response = self._rpc.http_req(http.post, path, json=schedule_json)

        return response.json()

    def update_schedule(self, job_id, schedule_id, schedule_resource):
        """Gets the schedules for a given job

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: json of schedules
        """
        job_id = util.normalize_marathon_id_path(job_id)
        if hasattr(schedule_resource, 'read'):
            schedule_json = json.load(schedule_resource)
        else:
            schedule_json = schedule_resource

        path = 'v1/jobs{}/schedules/{}'.format(job_id, schedule_id)
        response = self._rpc.http_req(http.put, path, json=schedule_json)

        return response.json()

    def remove_schedule(self, job_id, schedule_id):
        """Completely removes the requested application.

        :param job_id: the ID of the job to remove
        :type job_id: str
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        job_id = util.normalize_marathon_id_path(job_id)
        schedule_id = util.normalize_marathon_id_path(schedule_id)
        path = 'v1/jobs{}/schedules/{}'.format(job_id, schedule_id)
        self._rpc.http_req(http.delete, path)

    def run_job(self, job_id):
        """Add a new job.

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: None
        """

        job_id = util.normalize_marathon_id_path(job_id)
        path = '/v1/jobs{}/runs'.format(job_id)
        response = self._rpc.http_req(http.post, path)

        return response.json()

    def get_runs(self, job_id):
        """Gets the schedules for a given job

        :param job_id: the ID of the job to remove
        :type job_id: str
        :rtype: json of schedules
        """
        job_id = util.normalize_marathon_id_path(job_id)
        path = '/v1/jobs{}/runs'.format(job_id)
        response = self._rpc.http_req(http.get, path)
        return response.json()

    def get_run(self, job_id, run_id):
        """Add a new job.

        :param job_id: the ID of the job
        :type job_id: str
        :param run_id: the ID of the job run
        :type run_id: str
        :rtype: None
        """

        job_id = util.normalize_marathon_id_path(job_id)
        run_id = util.normalize_marathon_id_path(run_id)
        path = '/v1/jobs{}/runs{}'.format(job_id, run_id)
        response = self._rpc.http_req(http.get, path)
        return response.json()

    def kill_run(self, job_id, run_id):
        """Add a new job.

        :param job_id: the ID of the job
        :type job_id: str
        :param run_id: the ID of the job run to remove
        :type run_id: str
        :rtype: None
        """

        job_id = util.normalize_marathon_id_path(job_id)
        run_id = util.normalize_marathon_id_path(run_id)
        path = '/v1/jobs{}/runs{}/actions/stop'.format(job_id, run_id)
        self._rpc.http_req(http.post, path)

    @staticmethod
    def _job_id_path_format(url_path_template, id_path):
        """Substitutes a Metronome "ID path" into a URL path format string,
        ensuring the result is well-formed.

        All leading and trailing slashes in the ID will be removed, and the ID
        will have all URL-unsafe characters escaped, as if by
        urllib.parse.quote().

        :param url_path_template: format string for the path portion of a URL,
                                  with a single format specifier (i.e. {})
                                  where the "ID path" should be inserted
        :type url_path_template: str
        :param id_path: a Job "ID path", e.g. app, group, or pod ID
        :type id_path: str
        :returns: the url path template with the ID inserted
        :rtype: str
        """

        normalized_id_path = urllib.parse.quote(id_path.strip('/'))
        return url_path_template.format(normalized_id_path)

    @staticmethod
    def _force_params(force):
        """Returns the query parameters that signify the provided force value.

        :param force: whether to override running deployments
        :type force: bool
        :rtype: {} | None
        """

        return {'stopCurrentJobRuns': 'true'} if force else None

    @staticmethod
    def _parse_json(response):
        """Attempts to parse the body of the given response as JSON.

        Raises DCOSException if parsing fails.

        :param response: the response containing the body to parse
        :type response: requests.Response
        :return: the parsed JSON
        :rtype: {} | [] | str | int | float | bool | None
        """

        try:
            return response.json()
        except Exception:
            template = ('Error: Response from Metronome was not in expected '
                        'JSON format:\n{}')
            raise DCOSException(template.format(response.text))


def _check_capability():
    """
    The function checks if cluster has metronome capability.

    :raises: DCOSException if cluster does not have metronome capability
    """

    manager = packagemanager.PackageManager(cosmos.get_cosmos_url())
    if not manager.has_capability('METRONOME'):
        raise DCOSException(
            'DC/OS backend does not support metronome capabilities in this '
            'version. Must be DC/OS >= 1.8')
