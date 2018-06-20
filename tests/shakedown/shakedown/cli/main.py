import click
import json
import importlib
import os
import sys

from shakedown.cli.helpers import *
from shakedown.dcos import dcos_url


@click.command('shakedown')
@click.argument('tests', nargs=-1)
@click.option('-u', '--dcos-url', envvar='SHAKEDOWN_DCOS_URL', help='URL to a running DC/OS cluster.')
@click.option('-f', '--fail', envvar='SHAKEDOWN_FAIL', type=click.Choice(['fast', 'never']), default='never', help='Sepcify whether to continue testing when encountering failures. (default: never)')
@click.option('-m', '--timeout', envvar='SHAKEDOWN_TIMEOUT', default=1800, help='Seconds after which to terminate a running test')
@click.option('--ssh-user', envvar='SHAKEDOWN_SSH_USER', help='Username for cluster ssh authentication')
@click.option('-i', '--ssh-key-file', envvar='SHAKEDOWN_SSH_KEY_FILE', type=click.Path(), help='Path to the SSH keyfile to use for authentication.')
@click.option('-q', '--quiet', envvar='SHAKEDOWN_QUIET', is_flag=True, help='Suppress all superfluous output.')
@click.option('-k', '--ssl-no-verify', envvar='SHAKEDOWN_SSL_NO_VERIFY', is_flag=True, help='Suppress SSL certificate verification.')
@click.option('-o', '--stdout', envvar='SHAKEDOWN_STDOUT', type=click.Choice(['pass', 'fail', 'skip', 'all', 'none']), help='Print the standard output of tests with the specified result. (default: fail)')
@click.option('-s', '--stdout-inline', envvar='SHAKEDOWN_STDOUT_INLINE', is_flag=True, help='Display output inline rather than after test phase completion.')
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

    # Read configuration options from ~/.shakedown (if exists)
    args = read_config(args)

    # Set configuration defaults
    args = set_config_defaults(args)

    if args['quiet']:
        shakedown.cli.quiet = True

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

    # required modules and their 'version' method
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

    class shakedown:
        """ This encapsulates a PyTest wrapper plugin
        """

        state = {}

        stdout = []

        tests = {
            'file': {},
            'test': {}
        }

        report_stats = {
            'passed':[],
            'skipped':[],
            'failed':[],
            'total_passed':0,
            'total_skipped':0,
            'total_failed':0,
        }


        def output(title, state, text, status=True):
            """ Capture and display stdout/stderr output

                :param title: the title of the output box (eg. test name)
                :type title: str
                :param state: state of the result (pass, fail)
                :type state: str
                :param text: the stdout/stderr output
                :type text: str
                :param status: whether to output a status marker
                :type status: bool
            """
            if state == 'fail':
                schr = fchr('FF')
            elif state == 'pass':
                schr = fchr('PP')
            elif state == 'skip':
                schr = fchr('SK')
            else:
                schr = ''

            if status:
                if not args['stdout_inline']:
                    if state == 'fail':
                        echo(schr, d='fail')
                    elif state == 'pass':
                        echo(schr, d='pass')
                else:
                    if not text:
                        if state == 'fail':
                            echo(schr, d='fail')
                        elif state == 'pass':
                            if '::' in title:
                                echo(title.split('::')[-1], d='item-min', n=False)
                            echo(schr, d='pass')

            if text and args['stdout'] in [state, 'all']:
                o = decorate(schr + ': ', 'quote-head-' + state)
                o += click.style(decorate(title, style=state), bold=True) + "\n"
                o += decorate(str(text).strip(), style='quote-' + state)

                if args['stdout_inline']:
                    echo(o)
                else:
                    shakedown.stdout.append(o)


        def pytest_collectreport(self, report):
            """ Collect and validate individual test files
            """

            if not 'collect' in shakedown.state:
                shakedown.state['collect'] = 1
                echo('Collecting and validating test files...', d='step-min')

            if report.nodeid:
                echo(report.nodeid, d='item-maj', n=False)

                state = None

                if report.failed:
                    state = 'fail'
                if report.passed:
                    state = 'pass'
                if report.skipped:
                    state = 'skip'

                if state:
                    if report.longrepr:
                        shakedown.output(report.nodeid, state, report.longrepr)
                    else:
                        shakedown.output(report.nodeid, state, None)


        def pytest_sessionstart(self):
            """ Tests have been collected, begin running them...
            """

            echo('Initiating testing phase...', d='step-maj')


        def pytest_report_teststatus(self, report):
            """ Print report results to the console as they are run
            """

            try:
                report_file, report_test = report.nodeid.split('::', 1)
            except ValueError:
                return

            if not 'test' in shakedown.state:
                shakedown.state['test'] = 1
                echo('Running individual tests...', d='step-min')

            if not report_file in shakedown.tests['file']:
                shakedown.tests['file'][report_file] = 1
                echo(report_file, d='item-maj')
            if not report.nodeid in shakedown.tests['test']:
                shakedown.tests['test'][report.nodeid] = {}
                if args['stdout_inline']:
                    echo('')
                    echo(report_test + ':', d='item-min')
                else:
                    echo(report_test, d='item-min', n=False)

            if report.failed:
                shakedown.tests['test'][report.nodeid]['fail'] = True

            if report.when == 'teardown' and not 'tested' in shakedown.tests['test'][report.nodeid]:
                shakedown.output(report.nodeid, 'pass', None)

            # Suppress excess terminal output
            return report.outcome, None, None


        def pytest_runtest_logreport(self, report):
            """ Log the [stdout, stderr] results of tests if desired
            """

            state = None

            for secname, content in report.sections:
                if report.failed:
                    state = 'fail'
                if report.passed:
                    state = 'pass'
                if report.skipped:
                    state = 'skip'

                if state and secname != 'Captured stdout call':
                    module = report.nodeid.split('::', 1)[0]
                    cap_type = secname.split(' ')[-1]

                    if not 'setup' in shakedown.tests['test'][report.nodeid]:
                        shakedown.tests['test'][report.nodeid]['setup'] = True
                        shakedown.output(module + ' ' + cap_type, state, content, False)
                    elif cap_type == 'teardown':
                        shakedown.output(module + ' ' + cap_type, state, content, False)
                elif state and report.when == 'call':
                    if 'tested' in shakedown.tests['test'][report.nodeid]:
                        shakedown.output(report.nodeid, state, content, False)
                    else:
                        shakedown.tests['test'][report.nodeid]['tested'] = True
                        shakedown.output(report.nodeid, state, content)

            # Capture execution crashes
            if hasattr(report.longrepr, 'reprcrash'):
                longreport = report.longrepr

                if 'tested' in shakedown.tests['test'][report.nodeid]:
                    shakedown.output(report.nodeid, 'fail', 'error: ' + str(longreport.reprcrash), False)
                else:
                    shakedown.tests['test'][report.nodeid]['tested'] = True
                    shakedown.output(report.nodeid, 'fail', 'error: ' + str(longreport.reprcrash))


        def pytest_sessionfinish(self, session, exitstatus):
            """ Testing phase is complete; print extra reports (stdout/stderr, JSON) as requested
            """

            echo('Test phase completed.', d='step-maj')

            if ('stdout' in args and args['stdout']) and shakedown.stdout:
                for output in shakedown.stdout:
                    echo(output)

    opts = ['-q', '--tb=no', "--timeout={}".format(args['timeout'])]

    if args['fail'] == 'fast':
        opts.append('-x')

    if args['pytest_option']:
        for opt in args['pytest_option']:
            opts.append(opt)

    if args['stdout_inline']:
        opts.append('-s')

    if args['tests']:
        tests_to_run = []
        for test in args['tests']:
            tests_to_run.extend(test.split())
        for test in tests_to_run:
            opts.append(test)

    exitstatus = imported['pytest'].main(opts, plugins=[shakedown()])

    sys.exit(exitstatus)
