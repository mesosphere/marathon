import json

from six.moves import urllib

from dcos import config, http, rpcclient, util
from dcos.errors import DCOSException, DCOSHTTPException

logger = util.get_logger(__name__)


def create_client(toml_config=None):
    """Creates a Marathon client with the supplied configuration.

    :param toml_config: configuration dictionary
    :type toml_config: config.Toml
    :returns: Marathon client
    :rtype: dcos.marathon.Client
    """

    if toml_config is None:
        toml_config = config.get_config()

    marathon_url = _get_marathon_url(toml_config)
    timeout = config.get_config_val('core.timeout') or http.DEFAULT_TIMEOUT
    rpc_client = rpcclient.create_client(marathon_url, timeout)

    logger.info('Creating marathon client with: %r', marathon_url)
    return Client(rpc_client)


def _get_marathon_url(toml_config):
    """
    :param toml_config: configuration dictionary
    :type toml_config: config.Toml
    :returns: marathon base url
    :rtype: str
    """

    marathon_url = config.get_config_val('marathon.url', toml_config)
    if marathon_url is None:
        dcos_url = config.get_config_val('core.dcos_url', toml_config)
        if dcos_url is None:
            raise config.missing_config_exception(['core.dcos_url'])
        marathon_url = urllib.parse.urljoin(dcos_url, 'service/marathon/')

    return marathon_url


class Client(object):
    """Class for talking to the Marathon server.

    :param rpc_client: provides a method for making HTTP requests
    :type rpc_client: _RpcClient
    """

    def __init__(self, rpc_client):
        self._rpc = rpc_client

    def get_about(self):
        """Returns info about Marathon instance

        :returns Marathon information
        :rtype: dict
        """

        response = self._rpc.http_req(http.get, 'v2/info')

        return response.json()

    def ping(self):
        """Hits the Marathon ping endpoint

        :returns Pong
        :rtype: str
        """

        response = self._rpc.http_req(http.get, 'ping')
        return response.text

    def get_app(self, app_id, version=None):
        """Returns a representation of the requested application version. If
        version is None the return the latest version.

        :param app_id: the ID of the application
        :type app_id: str
        :param version: application version as a ISO8601 datetime
        :type version: str
        :returns: the requested Marathon application
        :rtype: dict
        """

        app_id = util.normalize_marathon_id_path(app_id)
        if version is None:
            path = 'v2/apps{}'.format(app_id)
        else:
            path = 'v2/apps{}/versions/{}'.format(app_id, version)

        response = self._rpc.http_req(http.get, path)

        # Looks like Marathon return different JSON for versions
        if version is None:
            return response.json().get('app')
        else:
            return response.json()

    def get_groups(self):
        """Get a list of known groups.

        :returns: list of known groups
        :rtype: list of dict
        """

        response = self._rpc.http_req(http.get, 'v2/groups')
        return response.json().get('groups')

    def get_group(self, group_id, version=None):
        """Returns a representation of the requested group version. If
        version is None the return the latest version.

        :param group_id: the ID of the application
        :type group_id: str
        :param version: application version as a ISO8601 datetime
        :type version: str
        :returns: the requested Marathon application
        :rtype: dict
        """

        group_id = util.normalize_marathon_id_path(group_id)
        if version is None:
            path = 'v2/groups{}'.format(group_id)
        else:
            path = 'v2/groups{}/versions/{}'.format(group_id, version)

        response = self._rpc.http_req(http.get, path)
        return response.json()

    def get_app_versions(self, app_id, max_count=None):
        """Asks Marathon for all the versions of the Application up to a
        maximum count.

        :param app_id: the ID of the application or group
        :type app_id: str
        :param max_count: the maximum number of version to fetch
        :type max_count: int
        :returns: a list of all the version of the application
        :rtype: [str]
        """

        if max_count is not None and max_count <= 0:
            raise DCOSException(
                'Maximum count must be a positive number: {}'.format(max_count)
            )

        app_id = util.normalize_marathon_id_path(app_id)

        path = 'v2/apps{}/versions'.format(app_id)

        response = self._rpc.http_req(http.get, path)

        if max_count is None:
            return response.json().get('versions')
        else:
            return response.json().get('versions')[:max_count]

    def get_apps(self):
        """Get a list of known applications.

        :returns: list of known applications
        :rtype: [dict]
        """

        response = self._rpc.http_req(http.get, 'v2/apps')
        return response.json().get('apps')

    def get_apps_for_framework(self, framework_name):
        """ Return all apps running the given framework.

        :param framework_name: framework name
        :type framework_name: str
        :rtype: [dict]
        """

        return [app for app in self.get_apps()
                if app.get('labels', {}).get(
                    'DCOS_PACKAGE_FRAMEWORK_NAME') == framework_name]

    def add_app(self, app_resource):
        """Add a new application.

        :param app_resource: application resource
        :type app_resource: dict, bytes or file
        :returns: the application description
        :rtype: dict
        """

        # The file type exists only in Python 2, preventing type(...) is file.
        if hasattr(app_resource, 'read'):
            app_json = json.load(app_resource)
        else:
            app_json = app_resource

        response = self._rpc.http_req(http.post, 'v2/apps', json=app_json)
        return response.json().get('deployments', {})[0].get('id')

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
        :returns: the response from Marathon
        :rtype: requests.Response
        """

        path_template = 'v2/{}/{{}}'.format(resource_type)
        path = self._marathon_id_path_format(path_template, resource_id)
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
                        'JSON response from Marathon:\n{}')
            rendered_json = json.dumps(body_json, indent=2, sort_keys=True)
            raise DCOSException(template.format(rendered_json))

    def update_app(self, app_id, payload, force=False):
        """Update an application.

        :param app_id: the application id
        :type app_id: str
        :param payload: the json payload
        :type payload: dict
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: str
        """

        return self._update('apps', app_id, payload, force)

    def update_group(self, group_id, payload, force=False):
        """Update a group.

        :param group_id: the group id
        :type group_id: str
        :param payload: the json payload
        :type payload: dict
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: str
        """

        return self._update('groups', group_id, payload, force)

    def scale_app(self, app_id, instances, force=False):
        """Scales an application to the requested number of instances.

        :param app_id: the ID of the application to scale
        :type app_id: str
        :param instances: the requested number of instances
        :type instances: int
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: str
        """

        app_id = util.normalize_marathon_id_path(app_id)
        params = self._force_params(force)
        path = 'v2/apps{}'.format(app_id)

        response = self._rpc.http_req(http.put,
                                      path,
                                      params=params,
                                      json={'instances': int(instances)})

        deployment = response.json().get('deploymentId')
        return deployment

    def scale_group(self, group_id, scale_factor, force=False):
        """Scales a group with the requested scale-factor.
        :param group_id: the ID of the group to scale
        :type group_id: str
        :param scale_factor: the requested value of scale-factor
        :type scale_factor: float
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: bool
        """

        group_id = util.normalize_marathon_id_path(group_id)
        params = self._force_params(force)
        path = 'v2/groups{}'.format(group_id)

        response = self._rpc.http_req(http.put,
                                      path,
                                      params=params,
                                      json={'scaleBy': scale_factor})

        deployment = response.json().get('deploymentId')
        return deployment

    def stop_app(self, app_id, force=False):
        """Scales an application to zero instances.

        :param app_id: the ID of the application to stop
        :type app_id: str
        :param force: whether to override running deployments
        :type force: bool
        :returns: the resulting deployment ID
        :rtype: bool
        """

        return self.scale_app(app_id, 0, force)

    def remove_app(self, app_id, force=False):
        """Completely removes the requested application.

        :param app_id: the ID of the application to remove
        :type app_id: str
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        app_id = util.normalize_marathon_id_path(app_id)
        params = self._force_params(force)
        path = 'v2/apps{}'.format(app_id)
        self._rpc.http_req(http.delete, path, params=params)

    def remove_group(self, group_id, force=False):
        """Completely removes the requested application.

        :param group_id: the ID of the application to remove
        :type group_id: str
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        group_id = util.normalize_marathon_id_path(group_id)
        params = self._force_params(force)
        path = 'v2/groups{}'.format(group_id)

        self._rpc.http_req(http.delete, path, params=params)

    def kill_tasks(self, app_id, scale=None, host=None):
        """Kills the tasks for a given application,
        and can target a given agent, with a future target scale

        :param app_id: the id of the application to restart
        :type app_id: str
        :param scale: Scale the app down after killing the specified tasks
        :type scale: bool
        :param host: host to target restarts on
        :type host: string
        """
        params = {}
        app_id = util.normalize_marathon_id_path(app_id)
        if host:
            params['host'] = host
        if scale:
            params['scale'] = scale
        path = 'v2/apps{}/tasks'.format(app_id)
        response = self._rpc.http_req(http.delete, path, params=params)
        return response.json()

    def kill_and_scale_tasks(self, task_ids, scale=None, wipe=None):
        """Kills the tasks for a given application,
        and can target a given agent, with a future target scale

        :param task_ids: a list of task ids to kill
        :type task_ids: list
        :param scale: Scale the app down after killing the specified tasks
        :type scale: bool
        :param wipe: whether remove reservations and persistent volumes.
        :type wipe: bool
        :returns: If scale=false, all tasks that were killed are returned.
                  If scale=true, than a deployment is triggered and the
                  deployment id and version returned.
        :rtype: list | dict
        """

        params = {}
        path = 'v2/tasks/delete'

        if scale:
            params['scale'] = scale
        if wipe:
            params['wipe'] = wipe

        response = self._rpc.http_req(http.post,
                                      path,
                                      params=params,
                                      json={'ids': task_ids})

        return response.json()

    def restart_app(self, app_id, force=False):
        """Performs a rolling restart of all of the tasks.

        :param app_id: the id of the application to restart
        :type app_id: str
        :param force: whether to override running deployments
        :type force: bool
        :returns: the deployment id and version
        :rtype: dict
        """

        app_id = util.normalize_marathon_id_path(app_id)
        params = self._force_params(force)
        path = 'v2/apps{}/restart'.format(app_id)

        response = self._rpc.http_req(http.post, path, params=params)
        return response.json()

    def get_deployment(self, deployment_id):
        """Returns a deployment.

        :param deployment_id: the deployment id
        :type deployment_id: str
        :returns: a deployment
        :rtype: dict
        """

        response = self._rpc.http_req(http.get, 'v2/deployments')
        deployment = next(
            (deployment for deployment in response.json()
             if deployment_id == deployment['id']),
            None)

        return deployment

    def get_deployments(self, app_id=None):
        """Returns a list of deployments, optionally limited to an app.

        :param app_id: the id of the application
        :type app_id: str
        :returns: a list of deployments
        :rtype: list of dict
        """

        response = self._rpc.http_req(http.get, 'v2/deployments')

        if app_id is not None:
            app_id = util.normalize_marathon_id_path(app_id)
            deployments = [
                deployment for deployment in response.json()
                if app_id in deployment['affectedApps']
            ]
        else:
            deployments = response.json()

        return deployments

    def _cancel_deployment(self, deployment_id, force):
        """Cancels an application deployment.

        :param deployment_id: the deployment id
        :type deployment_id: str
        :param force: if set to `False`, stop the deployment and
                      create a new rollback deployment to reinstate the
                      previous configuration. If set to `True`, simply stop the
                      deployment.
        :type force: bool
        :returns: cancelation deployment
        :rtype: dict
        """

        params = self._force_params(force)
        path = 'v2/deployments/{}'.format(deployment_id)

        response = self._rpc.http_req(http.delete, path, params=params)

        if force:
            return None
        else:
            return response.json()

    def rollback_deployment(self, deployment_id):
        """Rolls back an application deployment.

        :param deployment_id: the deployment id
        :type deployment_id: str
        :returns: cancelation deployment
        :rtype: dict
        """

        return self._cancel_deployment(deployment_id, False)

    def stop_deployment(self, deployment_id):
        """Stops an application deployment.

        :param deployment_id: the deployment id
        :type deployment_id: str
        :rtype: None
        """

        self._cancel_deployment(deployment_id, True)

    def get_tasks(self, app_id):
        """Returns a list of tasks, optionally limited to an app.

        :param app_id: the id of the application to restart
        :type app_id: str
        :returns: a list of tasks
        :rtype: [dict]
        """

        response = self._rpc.http_req(http.get, 'v2/tasks')

        if app_id is not None:
            app_id = util.normalize_marathon_id_path(app_id)
            tasks = [
                task for task in response.json()['tasks']
                if app_id == task['appId']
            ]
        else:
            tasks = response.json()['tasks']

        return tasks

    def get_task(self, task_id):
        """Returns a task

        :param task_id: the id of the task
        :type task_id: str
        :returns: a tasks
        :rtype: dict
        """

        response = self._rpc.http_req(http.get, 'v2/tasks')

        task = next(
            (task for task in response.json()['tasks']
             if task_id == task['id']),
            None)

        return task

    def stop_task(self, task_id, wipe=None):
        """Stops a task.

        :param task_id: the ID of the task
        :type task_id: str
        :param wipe: whether remove reservations and persistent volumes.
        :type wipe: bool
        :returns: a tasks
        :rtype: dict
        """

        if not wipe:
            params = None
        else:
            params = {'wipe': 'true'}

        response = self._rpc.http_req(http.post,
                                      'v2/tasks/delete',
                                      params=params,
                                      json={'ids': [task_id]})

        task = next(
            (task for task in response.json()['tasks']
             if task_id == task['id']),
            None)

        return task

    def create_group(self, group_resource):
        """Add a new group.

        :param group_resource: grouplication resource
        :type group_resource: dict, bytes or file
        :returns: the group description
        :rtype: dict
        """

        # The file type exists only in Python 2, preventing type(...) is file.
        if hasattr(group_resource, 'read'):
            group_json = json.load(group_resource)
        else:
            group_json = group_resource

        response = self._rpc.http_req(http.post, 'v2/groups', json=group_json)
        return response.json().get("deploymentId")

    def get_leader(self):
        """ Get the leading marathon instance.

        :returns: string of the form <ip>:<port>
        :rtype: str
        """

        response = self._rpc.http_req(http.get, 'v2/leader')
        return response.json().get('leader')

    def delete_leader(self):
        """ Delete the leading marathon instance.
        """
        response = self._rpc.http_req(http.delete, 'v2/leader')
        return response.json()

    def add_pod(self, pod_json):
        """Add a new pod.

        :param pod_json: JSON pod definition
        :type pod_json: dict
        :returns: description of created pod
        :rtype: dict
        """

        response = self._rpc.http_req(http.post, 'v2/pods', json=pod_json)
        return response.headers.get('Marathon-Deployment-Id')

    def remove_pod(self, pod_id, force=False):
        """Completely removes the requested pod.

        :param pod_id: the ID of the pod to remove
        :type pod_id: str
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        path = self._marathon_id_path_format('v2/pods/{}', pod_id)
        params = self._force_params(force)
        self._rpc.http_req(http.delete, path, params=params)

    def show_pod(self, pod_id):
        """Returns a representation of the requested pod.

        :param pod_id: the ID of the pod
        :type pod_id: str
        :returns: the requested Marathon pod
        :rtype: dict
        """

        path = self._marathon_id_path_format('v2/pods/{}::status', pod_id)
        response = self._rpc.http_req(http.get, path)
        return self._parse_json(response)

    def list_pod(self):
        """Get a list of known pods.

        :returns: list of known pods
        :rtype: [dict]
        """

        response = self._rpc.http_req(http.get, 'v2/pods/::status')
        return self._parse_json(response)

    def update_pod(self, pod_id, pod_json, force=False):
        """Update a pod.

        :param pod_id: the pod ID
        :type pod_id: str
        :param pod_json: JSON pod definition
        :type pod_json: {}
        :param force: whether to override running deployments
        :type force: bool
        :rtype: None
        """

        response = self._update_req('pods', pod_id, pod_json, force)
        deployment_id_header_name = 'Marathon-Deployment-Id'
        deployment_id = response.headers.get(deployment_id_header_name)

        if deployment_id is None:
            template = 'Error: missing "{}" header from Marathon response'
            raise DCOSException(template.format(deployment_id_header_name))

        return deployment_id

    def kill_pod_instances(self, pod_id, instance_ids):
        """Kills the given instances of the specified pod.

        :param pod_id: the pod to delete instances from
        :type pod_id: str
        :param instance_ids: the IDs of the instances to kill
        :type instance_ids: [str]
        :returns: the status JSON objects for the killed instances
        :rtype: [{}]
        """

        path = self._marathon_id_path_format('v2/pods/{}::instances', pod_id)
        response = self._rpc.http_req(http.delete, path, json=instance_ids)
        return self._parse_json(response)

    def pod_feature_supported(self):
        """Return whether or not this client is communicating with a version
        of Marathon that supports pod operations.

        :rtype: bool
        """

        # Allow response objects to be returned from `http_req` on status 404,
        # while handling all other exceptions as usual
        def test_for_pods(url, **kwargs):
            try:
                return http.head(url, **kwargs)
            except DCOSHTTPException as e:
                if e.status() == 404:
                    return e.response
                raise

        response = self._rpc.http_req(test_for_pods, 'v2/pods')
        return response.status_code // 100 == 2

    def get_queued_app(self, app_id):
        """Returns app information inside the launch queue.

        :param app_id: the app id
        :type app_id: str
        :returns: app information inside the launch queue
        :rtype: dict
        """

        response = self._rpc.http_req(http.get,
                                      'v2/queue?embed=lastUnusedOffers')
        app = next(
            (app for app in response.json().get('queue')
             if app_id == get_app_or_pod_id(app)),
            None)

        return app

    def get_queued_apps(self):
        """Returns the content of the launch queue,
        including the apps which should be scheduled.

        :returns: a list of to be scheduled apps, including debug information
        :rtype: list of dict
        """

        response = self._rpc.http_req(http.get, 'v2/queue')

        return response.json().get('queue')

    def get_plugins(self):
        """Get a list of known plugins.

        :returns: list of known plugins
        :rtype: [dict]
        """

        response = self._rpc.http_req(http.get, 'v2/plugins')
        return response.json()

    @staticmethod
    def _marathon_id_path_format(url_path_template, id_path):
        """Substitutes a Marathon "ID path" into a URL path format string,
        ensuring the result is well-formed.

        All leading and trailing slashes in the ID will be removed, and the ID
        will have all URL-unsafe characters escaped, as if by
        urllib.parse.quote().

        :param url_path_template: format string for the path portion of a URL,
                                  with a single format specifier (i.e. {})
                                  where the "ID path" should be inserted
        :type url_path_template: str
        :param id_path: a Marathon "ID path", e.g. app, group, or pod ID
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

        return {'force': 'true'} if force else None

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
            template = ('Error: Response from Marathon was not in expected '
                        'JSON format:\n{}')
            raise DCOSException(template.format(response.text))


def get_app_or_pod_id(app_or_pod):
    """Gets the app or pod ID from the given app or pod

        :param app_or_pod: app or pod definition
        :type app_or_pod: requests.Response
        :return: app or pod id
        :rtype: str
        """
    return app_or_pod.get('app', app_or_pod.get('pod', {})).get('id')
