import itertools

from dcos import cosmos, emitting, packagemanager, subcommand, util
from dcos.errors import DCOSException

logger = util.get_logger(__name__)

emitter = emitting.FlatEmitter()


def uninstall(pkg, package_name, remove_all, app_id, cli, app):
    """Uninstalls a package.

    :param pkg: package manager to uninstall with
    :type pkg: PackageManager
    :param package_name: The package to uninstall
    :type package_name: str
    :param remove_all: Whether to remove all instances of the named app
    :type remove_all: boolean
    :param app_id: App ID of the app instance to uninstall
    :type app_id: str
    :param cli: Whether to remove the CLI only
    :type cli: boolean
    :param app: Whether to remove app only
    :type app: boolean
    :rtype: None
    """

    installed = installed_packages(
        pkg, None, package_name, cli_only=False)
    installed_pkg = next(iter(installed), None)

    if installed_pkg:
        installed_cli = installed_pkg.get("command")
        installed_app = installed_pkg.get("apps") or []
    else:
        msg = 'Package [{}]'.format(package_name)
        if app_id is not None:
            app_id = util.normalize_marathon_id_path(app_id)
            msg += " with id [{}]".format(app_id)
        msg += " is not installed"
        raise DCOSException(msg)

    # Having `app == True` means that the user supplied an explicit `--app`
    # flag on the command line. Having `cli == True` means that the user
    # supplied an explicit `--cli` flag on the command line. If either of these
    # is `True`, run the following.
    if app or cli:
        # This forces an unconditional uninstall of the app associated with the
        # supplied package (with different semantics depending on the values of
        # `remove_all` and `app_id` as described in the docstring for this
        # function).
        if app and installed_app:
            if not pkg.uninstall_app(package_name, remove_all, app_id):
                raise DCOSException("Couldn't uninstall package")

        # This forces an unconditional uninstall of the CLI associated with the
        # supplied package.
        if cli and installed_cli:
            if not subcommand.uninstall(package_name):
                raise DCOSException("Couldn't uninstall subcommand")

        return

    # Having both `app == False` and `cli == False` means that the user didn't
    # supply either `--app` or `--cli` on the command line. In this situation
    # we uninstall the app associated with the supplied package (if it exists)
    # just as if the user had explicitly passed `--app` on the command line.
    # However, we only uninstall the CLI associated with the package if the app
    # being uninstalled is the last one remaining on the system.  Otherwise, we
    # leave the CLI in place so other instances of the app can continue to
    # interact with it.
    if installed_app:
        if not pkg.uninstall_app(package_name, remove_all, app_id):
            raise DCOSException("Couldn't uninstall package")

    if installed_cli and (remove_all or len(installed_app) <= 1):
        if not subcommand.uninstall(package_name):
            raise DCOSException("Couldn't uninstall subcommand")


def uninstall_subcommand(distribution_name):
    """Uninstalls a subcommand.

    :param distribution_name: the name of the package
    :type distribution_name: str
    :returns: True if the subcommand was uninstalled
    :rtype: bool
    """

    return subcommand.uninstall(distribution_name)


def _matches_package_name(name, command_name):
    """
    :param name: the name of the package
    :type name: str
    :param command_name: the name of the command
    :type command_name: str
    :returns: True if the name is not defined or the package matches that name;
              False otherwise
    :rtype: bool
    """

    return name is None or command_name == name


def installed_packages(package_manager, app_id, package_name, cli_only):
    """Returns all installed packages in the format:

    [{
       'apps': [<id>],
       'command': {
         'name': <name>
       }
       ...<metadata>...
    }]

    :param init_client: The program to use to list packages
    :type init_client: object
    :param app_id: App ID of app to show
    :type app_id: str
    :param package_name: The package to show
    :type package_name: str
    :param cli_only: if True, returns only packages with locally installed
        subcommands, without retrieving the apps installed on the cluster
    :type cli_only: bool
    :returns: A list of installed packages matching criteria
    :rtype: [dict]
    """

    apps = []
    if not cli_only:
        apps = package_manager.installed_apps(package_name, app_id)

    subcommands = []
    for subcmd in installed_subcommands():
        if _matches_package_name(package_name, subcmd.name):
            subcmd_dict = subcmd.package_json()
            subcmd_dict['command'] = {'name': subcmd.name}
            subcommands.append(subcmd_dict)

    return merge_installed(apps, subcommands, app_id is not None, cli_only)


def installed_subcommands():
    """Returns all installed subcommands.

    :returns: all installed subcommands
    :rtype: [InstalledSubcommand]
    """

    return [subcommand.InstalledSubcommand(name) for name in
            subcommand.distributions()]


def merge_installed(apps, subcommands, app_only, cli_only):
    """Combines collections of installed apps and subcommands, merging
    elements from the same package.

    :param apps: information on each running app in the cluster; must have
        'name' and 'appId' keys
    :type apps: [dict]
    :param subcommands: information on each subcommand installed locally; must
        have a 'name' key
    :type subcommands: [dict]
    :param app_only: if True, only returns elements that have an app
    :type app_only: bool
    :param cli_only: if True, only returns elements that have a subcommand
    :type cli_only: bool
    :returns: the resulting merged collection, with one element per package
    :rtype: [{}]
    """

    indexed_apps = {}
    grouped_apps = itertools.groupby(apps, key=lambda app: app['name'])
    for name, app_group in grouped_apps:
        app_list = list(app_group)
        pkg = app_list[0]
        pkg['apps'] = sorted(app['appId'] for app in app_list)
        del pkg['appId']
        indexed_apps[name] = pkg

    indexed_subcommands = {subcmd['command']['name']: subcmd
                           for subcmd in subcommands}

    merged = []
    for name, app in indexed_apps.items():
        subcmd = indexed_subcommands.pop(name, {})
        if subcmd or not cli_only:
            app.update(subcmd)
            merged.append(app)

    if not app_only:
        merged.extend(indexed_subcommands.values())

    return merged


def get_package_manager():
    """Returns type of package manager to use

    :returns: PackageManager instance
    :rtype: packagemanager.PackageManager
    """
    cosmos_url = cosmos.get_cosmos_url()
    cosmos_manager = packagemanager.PackageManager(cosmos_url)
    if cosmos_manager.enabled():
        return cosmos_manager
    else:
        msg = ("This version of the DC/OS CLI is not supported for your "
               "cluster. Please downgrade the CLI to an older version: "
               "https://dcos.io/docs/usage/cli/update/#downgrade")
        raise DCOSException(msg)
