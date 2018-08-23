import base64
import collections
import functools

import six
from six.moves import urllib

from dcos import cosmos, emitting, util
from dcos.errors import (DCOSAuthenticationException,
                         DCOSAuthorizationException, DCOSBadRequest,
                         DCOSConnectionError, DCOSException, DCOSHTTPException,
                         DefaultError)

logger = util.get_logger(__name__)
emitter = emitting.FlatEmitter()


def cosmos_error(fn):
    """Decorator for errors returned from cosmos

    :param fn: function to check for errors from cosmos
    :type fn: function
    :rtype: requests.Response
    :returns: requests.Response
    """

    @functools.wraps(fn)
    def check_for_cosmos_error(*args, **kwargs):
        """Returns response from cosmos or raises exception

        :returns: Response or raises Exception
        :rtype: requests.Response
        """
        error_media_type = 'application/vnd.dcos.package.error+json;' \
                           'charset=utf-8;version=v1'
        response = fn(*args, **kwargs)
        content_type = response.headers.get('Content-Type')
        if content_type is None:
            raise DCOSHTTPException(response)
        elif error_media_type in content_type:
            logger.debug("Error: {}".format(response.json()))
            error_msg = _format_error_message(response.json())
            raise DCOSException(error_msg)
        return response

    return check_for_cosmos_error


class PackageManager:
    """Implementation of Package Manager using Cosmos"""

    def __init__(self, cosmos_url):
        self.cosmos_url = cosmos_url
        self.cosmos = cosmos.Cosmos(self.cosmos_url)

    def has_capability(self, capability):
        """Check if cluster has a capability.

        :param capability: capability name
        :type capability: string
        :return: does the cluster has capability
        :rtype: bool
        """

        if not self.enabled():
            return False

        try:
            response = self.cosmos.call_endpoint(
                'capabilities').json()
        except DCOSAuthenticationException:
            raise
        except DCOSAuthorizationException:
            raise
        except DCOSConnectionError:
            raise
        except Exception as e:
            logger.exception(e)
            return False

        if 'capabilities' not in response:
            logger.error(
                'Request to get cluster capabilities: {} '
                'returned unexpected response: {}. '
                'Missing "capabilities" field'.format(
                    urllib.parse.urljoin(self.cosmos_url, 'capabilities'),
                    response))
            return False

        return {'name': capability} in response['capabilities']

    def enabled(self):
        """Returns whether or not cosmos is enabled on specified dcos cluster

        :rtype: bool
        """
        return self.cosmos.enabled()

    def install_app(self, pkg, options, app_id):
        """Installs a package's application

        :param pkg: the package to install
        :type pkg: CosmosPackageVersion
        :param options: user supplied package parameters
        :type options: dict
        :param app_id: app ID for installation of this package
        :type app_id: str
        :rtype: None
        """

        params = {"packageName": pkg.name(), "packageVersion": pkg.version()}
        if options is not None:
            params["options"] = options
        if app_id is not None:
            params["appId"] = app_id

        self.cosmos_post("install", params)

    def uninstall_app(self, package_name, remove_all, app_id):
        """Uninstalls an app.

        :param package_name: The package to uninstall
        :type package_name: str
        :param remove_all: Whether to remove all instances of the named app
        :type remove_all: boolean
        :param app_id: App ID of the app instance to uninstall
        :type app_id: str
        :returns: whether uninstall was successful or not
        :rtype: bool
        """

        params = {"packageName": package_name}
        if remove_all is True:
            params["all"] = True
        if app_id is not None:
            params["appId"] = app_id

        response = self.cosmos_post("uninstall", params)
        results = response.json().get("results")

        uninstalled_versions = []
        for res in results:
            version = res.get("packageVersion")
            if version not in uninstalled_versions:
                emitter.publish(
                    DefaultError(
                        'Uninstalled package [{}] version [{}]'.format(
                            res.get("packageName"),
                            res.get("packageVersion"))))
                uninstalled_versions += [res.get("packageVersion")]

                if res.get("postUninstallNotes") is not None:
                    emitter.publish(
                        DefaultError(res.get("postUninstallNotes")))

        return True

    def search_sources(self, query):
        """package search

        :param query: query to search
        :type query: str
        :returns: list of package indicies of matching packages
        :rtype: [packages]
        """
        response = self.cosmos_post("search", {"query": query})
        return response.json()

    def get_package_version(self, package_name, package_version):
        """Returns PackageVersion of specified package

        :param package_name: package name
        :type package_name: str
        :param package_version: version of package
        :type package_version: str | None
        :rtype: PackageVersion
        """

        return CosmosPackageVersion(package_name, package_version,
                                    self.cosmos_url)

    def installed_apps(self, package_name, app_id):
        """List installed packages

        {
            'appId': <appId>,
            ..<package.json properties>..
        }

        :param package_name: the optional package to list
        :type package_name: str
        :param app_id: the optional application id to list
        :type app_id: str
        :rtype: [dict]
        """

        params = {}
        if package_name is not None:
            params["packageName"] = package_name
        if app_id is not None:
            params["appId"] = app_id

        list_response = self.cosmos_post("list", params).json()

        packages = []
        for pkg in list_response['packages']:
            result = pkg['packageInformation']['packageDefinition']

            result['appId'] = pkg['appId']
            packages.append(result)

        return packages

    def get_repos(self):
        """List locations of repos

        :returns: the list of repos, in resolution order or list
        :rtype: dict
        """

        return self.cosmos_post("repository/list", params={}).json()

    def add_repo(self, name, package_repo, index):
        """Add package repo and update repo with new repo

        :param name: name to call repo
        :type name: str
        :param package_repo: location of repo to add
        :type package_repo: str
        :param index: index to add this repo
        :type index: int
        :returns: current repo list
        :rtype: dict
        """

        params = {"name": name, "uri": package_repo}
        if index is not None:
            params["index"] = index
        response = self.cosmos_post("repository/add", params=params)
        return response.json()

    def remove_repo(self, name):
        """Remove package repo and update repo

        :param name: name of repo to remove
        :type name: str
        :returns: current repo list
        :rtype: dict
        """

        params = {"name": name}
        response = self.cosmos_post("repository/delete", params=params)
        return response.json()

    def package_add_local(self, dcos_package):
        """
         Adds a locally stored DC/OS package to DC/OS

        :param dcos_package: path to the DC/OS package
        :type dcos_package: None | str
        :return: Response to the package add request
        :rtype: requests.Response
        """
        try:
            with util.open_file(dcos_package, 'rb') as pkg:
                extra_headers = {
                    'Content-Type':
                        'application/vnd.dcos.'
                        'universe.package+zip;version=v1',
                    'X-Dcos-Content-MD5': util.md5_hash_file(pkg)
                }
                return self._post('add', headers=extra_headers, data=pkg)
        except DCOSHTTPException as e:
            if e.status() == 404:
                message = 'Your version of DC/OS ' \
                          'does not support this operation'
                raise DCOSException(message)
            else:
                raise e

    def package_add_remote(self, package_name, package_version):
        """
         Adds a remote DC/OS package to DC/OS

        :param package_name: name of the remote package to add
        :type package_name: None | str
        :param package_version: version of the remote package to add
        :type package_version: None | str
        :return: Response to the package add request
        :rtype: requests.Response
        """
        try:
            json = {'packageName': package_name}
            if package_version is not None:
                json['packageVersion'] = package_version
            return self._post('add', params=json)
        except DCOSHTTPException as e:
            if e.status() == 404:
                message = 'Your version of DC/OS ' \
                          'does not support this operation'
                raise DCOSException(message)
            else:
                raise e

    @cosmos_error
    def _post(self, request, params=None, headers=None, data=None):
        """Request to cosmos server

        :param request: type of request
        :type request: str
        :param params: body of request
        :type params: dict
        :param headers: list of headers for request in order of preference
        :type headers: [str]
        :param data: a file object
        :type: file
        :returns: Response
        :rtype: requests.Response
        """

        endpoint = 'package/{}'.format(request)
        try:
            return self.cosmos.call_endpoint(
                endpoint, headers, data=data, json=params)
        except DCOSAuthenticationException:
            raise
        except DCOSAuthorizationException:
            raise
        except DCOSBadRequest as e:
            return e.response
        except DCOSHTTPException as e:
            # let non authentication responses be handled by `cosmos_error` so
            # we can expose errors reported by cosmos
            return e.response

    def cosmos_post(self, request, params):
        """Request to cosmos server

        :param request: type of request
        :type request: str
        :param params: body of request
        :type params: dict
        :returns: Response
        :rtype: requests.Response
        """

        return self._post(request, params)


class CosmosPackageVersion():
    """Interface to a specific package version from cosmos"""

    def __init__(self, name, package_version, url):
        self._cosmos_url = url

        params = {"packageName": name}
        if package_version is not None:
            params["packageVersion"] = package_version
        response = PackageManager(url).cosmos_post("describe", params)

        self._package_json = response.json()
        self._content_type = response.headers['Content-Type']

    def version(self):
        """Returns the package version.

        :returns: The version of this package
        :rtype: str
        """

        return self.package_json()["version"]

    def name(self):
        """Returns the package name.

        :returns: The name of this package
        :rtype: str
        """

        return self.package_json()["name"]

    def package_json(self):
        """Returns the JSON content of the package definition.

        :returns: Package data
        :rtype: dict
        """

        if 'version=v2' in self._content_type:
            return self._package_json
        else:
            return self._package_json["package"]

    def package_response(self):
        """Returns the JSON content of the describe response.

        :returns: Package data
        :rtype: dict
        """

        return self._package_json

    def config_json(self):
        """Returns the JSON content of the config.json file.

        :returns: Package config schema
        :rtype: dict | None
        """

        return self.package_json().get("config")

    def resource_json(self):
        """Returns the JSON content of the resource.json file.

        :returns: Package resources
        :rtype: dict | None
        """

        return self.package_json().get("resource")

    def marathon_template(self):
        """Returns raw data from marathon.json

        :returns: raw data from marathon.json
        :rtype: str | None
        """

        template = self.package_json().get("marathon", {}).get(
            "v2AppMustacheTemplate"
        )

        return base64.b64decode(template) if template else None

    def marathon_json(self, options):
        """Returns the JSON content of the marathon.json template, after
        rendering it with options.

        :param options: the template options to use in rendering
        :type options: dict
        :rtype: dict
        """

        params = {
            "packageName": self.name(),
            "packageVersion": self.version()
        }
        if options:
            params["options"] = options
        response = PackageManager(
            self._cosmos_url
        ).cosmos_post("render", params)
        return response.json().get("marathonJson")

    def options(self, user_options):
        """Makes sure user supplied options are valid

        :param user_options: the template options to use in rendering
        :type user_options: dict
        :rtype: None
        """

        self.marathon_json(user_options)
        return None

    def cli_definition(self):
        """Returns the JSON content that defines a cli subcommand. Looks for
        "cli" property in resource.json first and if that is None, checks for
        command.json

        :returns: Package data
        :rtype: dict | None
        """

        return (self.resource_json() and self.resource_json().get("cli")) or (
            self.command_json()
        )

    def command_json(self):
        """Returns the JSON content of the command.json file.

        :returns: Package data
        :rtype: dict | None
        """

        return self.package_json().get("command")

    def package_versions(self):
        """Returns a list of available versions for this package

        :returns: package version
        :rtype: []
        """

        params = {"packageName": self.name(), "includePackageVersions": True}
        response = PackageManager(self._cosmos_url).cosmos_post(
            "list-versions", params)

        return list(
            version for (version, releaseVersion) in
            sorted(
                response.json().get("results").items(),
                key=lambda item: int(item[1]),  # release version
                reverse=True
            )
        )


def _format_error_message(error):
    """Returns formatted error message based on error type

    :param error: cosmos error
    :type error: dict
    :returns: formatted error
    :rtype: str
    """
    if error.get("type") == "AmbiguousAppId":
        helper = (".\nPlease use --app-id to specify the ID of the app "
                  "to uninstall, or use --all to uninstall all apps.")
        error_message = error.get("message") + helper
    elif error.get("type") == "MultipleFrameworkIds":
        helper = ". Manually shut them down using 'dcos service shutdown'"
        error_message = error.get("message") + helper
    elif error.get("type") == "JsonSchemaMismatch":
        error_message = _format_json_schema_mismatch_message(error)
    elif error.get("type") == "MarathonBadResponse":
        error_message = _format_marathon_bad_response_message(error)
    elif error.get('type') == 'NotImplemented':
        error_message = 'DC/OS has not been ' \
                        'configured to support this operation'
    else:
        error_message = error.get("message")

    return error_message


def _format_json_schema_mismatch_message(error):
    """Returns the formatted error message for JsonSchemaMismatch

    :param error: cosmos JsonSchemMismatch error
    :type error: dict
    :returns: formatted error
    :rtype: str
    """

    error_messages = ["Error: {}".format(error.get("message"))]
    for err in error.get("data").get("errors"):
        if err.get("unwanted"):
            reason = "Unexpected properties: {}".format(err["unwanted"])
            error_messages += [reason]
        if err.get("found"):
            found = "Found: {}".format(err["found"])
            error_messages += [found]
        if err.get("minimum"):
            found = "Required minimum: {}".format(err["minimum"])
            error_messages += [found]
        if err.get("expected"):
            expected = "Expected: {}".format(",".join(err["expected"]))
            error_messages += [expected]
        if err.get("missing"):
            missing = "Required parameter missing: {}".format(
                ",".join(err["missing"]),
            )
            error_messages += [missing]
        if err.get("instance"):
            pointer = err["instance"].get("pointer")
            formatted_path = pointer.lstrip("/").replace("/", ".")
            path = "Path: {}".format(formatted_path)
            error_messages += [path]

    error_messages += [
        "\nPlease create a JSON file with the appropriate options, and"
        " pass the /path/to/file as an --options argument."
    ]

    return "\n".join(error_messages)


def _format_marathon_bad_response_message(error):
    data = error.get("data")
    error_messages = [error.get("message")]
    if data is not None:
        for err in data.get("errors"):
            if err.get("error") and isinstance(err["error"], six.string_types):
                error_messages += [err["error"]]
            elif err.get("errors") and \
                    isinstance(err["errors"], collections.Sequence):
                error_messages += err["errors"]
    return "\n".join(error_messages)
