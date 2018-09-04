import click
import importlib
import sys

from shakedown.cli.helpers import *
from shakedown.dcos import dcos_url
from shakedown import http
from shakedown.dcos.spinner import *

@click.command('shakedown')
@click.argument('tests', nargs=-1)
@click.option('-u', '--dcos-url', envvar='SHAKEDOWN_DCOS_URL', help='URL to a running DC/OS cluster.')
@click.option('--ssh-user', envvar='SHAKEDOWN_SSH_USER', help='Username for cluster ssh authentication')
@click.option('-i', '--ssh-key-file', envvar='SHAKEDOWN_SSH_KEY_FILE', type=click.Path(), help='Path to the SSH keyfile to use for authentication.')
@click.option('-k', '--ssl-no-verify', envvar='SHAKEDOWN_SSL_NO_VERIFY', is_flag=True, help='Suppress SSL certificate verification.')
@click.option('-p', '--pytest-option', envvar='SHAKEDOWN_PYTEST_OPTION', multiple=True, help='Options flags to pass to pytest.')
@click.option('-t', '--oauth-token', envvar='SHAKEDOWN_OAUTH_TOKEN', help='OAuth token to use for DC/OS authentication.')
@click.option('-n', '--username', envvar='SHAKEDOWN_USERNAME', help='Username to use for DC/OS authentication.')
@click.option('-w', '--password', envvar='SHAKEDOWN_PASSWORD', hide_input=True, help='Password to use for DC/OS authentication.')
@click.option('--no-banner', envvar='SHAKEDOWN_NO_BANNER', is_flag=True, help='Suppress the product banner.')
@click.version_option(version=shakedown.VERSION)


def cli(**args):
    """ Shakedown is a DC/OS test-harness wrapper for the pytest tool.
    """
    import shakedown
    import logging
    import logging.config

    logging.config.fileConfig('logging.conf')

    # Read configuration options from ~/.shakedown (if exists)
    args = read_config(args)

    if not args['dcos_url']:
        try:
            args['dcos_url'] = dcos_url()
        except:
            click.secho('error: cluster URL not set, use --dcos-url or see --help for more information.', fg='red', bold=True)
            sys.exit(1)

    if not args['dcos_url']:
        click.secho('error: --dcos-url is a required option; see --help for more information.', fg='red', bold=True)
        sys.exit(1)

    if args['ssh_key_file']:
        shakedown.cli.ssh_key_file = args['ssh_key_file']

    if args['ssh_user']:
        shakedown.cli.ssh_user = args['ssh_user']

    if not args['no_banner']:
        echo(banner(), n=False)

    echo('Running pre-flight checks...', d='step-maj')

    # Required modules and their 'version' method
    imported = {}
    requirements = {
        'pytest': '__version__',
        'dcos': 'version'
    }

    for req in requirements:
        ver = requirements[req]

        echo("Checking for {} library...".format(req), d='step-min', n=False)
        try:
            imported[req] = importlib.import_module(req, package=None)
        except ImportError:
            click.secho("error: {p} is not installed; run 'pip install {p}'.".format(p=req), fg='red', bold=True)
            sys.exit(1)

        echo(getattr(imported[req], requirements[req]))

    # Making sure that the cluster url is reachable before proceeding
    def cluster_available_predicate(url):
        try:
            response = http.get(url, verify=False)
            return response.status_code == 200
        except Exception as e:
            return False

    echo('Waiting for DC/OS cluster to respond...', d='step-min')
    time_wait(lambda: cluster_available_predicate(args['dcos_url']), timeout_seconds=300)

    if shakedown.attach_cluster(args['dcos_url']):
        echo('Checking DC/OS cluster version...', d='step-min', n=False)
        echo(shakedown.dcos_version())
    else:
        with imported['dcos'].cluster.setup_directory() as temp_path:
            imported['dcos'].cluster.set_attached(temp_path)

            imported['dcos'].config.set_val('core.dcos_url', args['dcos_url'])
            if args['ssl_no_verify']:
                imported['dcos'].config.set_val('core.ssl_verify', 'False')

            try:
                imported['dcos'].cluster.setup_cluster_config(args['dcos_url'], temp_path, False)
            except:
                echo('Authenticating with DC/OS cluster...', d='step-min')
                authenticated = False
                token = imported['dcos'].config.get_config_val("core.dcos_acs_token")
                if token is not None:
                    echo('trying existing ACS token...', d='step-min', n=False)
                    try:
                        shakedown.dcos_leader()

                        authenticated = True
                        echo(fchr('PP'), d='pass')
                    except imported['dcos'].errors.DCOSException:
                        echo(fchr('FF'), d='fail')
                if not authenticated and args['oauth_token']:
                    try:
                        echo('trying OAuth token...', d='item-maj', n=False)
                        token = shakedown.authenticate_oauth(args['oauth_token'])

                        with stdchannel_redirected(sys.stderr, os.devnull):
                            imported['dcos'].config.set_val('core.dcos_acs_token', token)

                        authenticated = True
                        echo(fchr('PP'), d='pass')
                    except:
                       echo(fchr('FF'), d='fail')
                if not authenticated and args['username'] and args['password']:
                    try:
                        echo('trying username and password...', d='item-maj', n=False)
                        token = shakedown.authenticate(args['username'], args['password'])

                        with stdchannel_redirected(sys.stderr, os.devnull):
                            imported['dcos'].config.set_val('core.dcos_acs_token', token)

                        authenticated = True
                        echo(fchr('PP'), d='pass')
                    except:
                        echo(fchr('FF'), d='fail')

                if authenticated:
                    imported['dcos'].cluster.setup_cluster_config(args['dcos_url'], temp_path, False)

                    echo('Checking DC/OS cluster version...', d='step-min', n=False)
                    echo(shakedown.dcos_version())
                else:
                    click.secho("error: no authentication credentials or token found.", fg='red', bold=True)
                    sys.exit(1)

    opts = []

    if args['pytest_option']:
        for opt in args['pytest_option']:
            opts.append(opt)

    echo('Using pytest options: {}'.format(opts), d='step-maj')

    if args['tests']:
        tests_to_run = []
        for test in args['tests']:
            tests_to_run.extend(test.split())
        for test in tests_to_run:
            opts.append(test)

    exitstatus = imported['pytest'].main(opts)

    sys.exit(exitstatus)
