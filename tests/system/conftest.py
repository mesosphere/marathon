import logging
import logging.config

import os
import pandas as pd
import re
import time


class PandasReport(object):
    """Reports pytest test results in a Pandas dataframe.

    This plugin roughly follows https://github.com/pytest-dev/pytest-html/blob/master/pytest_html/plugin.py.
    """

    def __init__(self, output):
        file = os.path.expanduser(os.path.expandvars(output))
        self._output_file = os.path.abspath(file)
        self._report = pd.DataFrame(columns=['build_id', 'testsuite', 'testcase', 'status', 'error', 'timestamp'])
        self._py_ext_re = re.compile(r"\.py$")
        self._build_id = 0

    def mangle_test_address(self, address):
        """Forked from https://github.com/pytest-dev/pytest/blob/master/src/_pytest/junitxml.py"""
        path, possible_open_bracket, params = address.partition("[")
        names = path.split("::")
        try:
            names.remove("()")
        except ValueError:
            pass
        # convert file path to dotted path
        names[0] = names[0].replace("/", ".")
        names[0] = self._py_ext_re.sub("", names[0])
        # put any params back
        names[-1] += possible_open_bracket + params

        return names

    def _save_report(self):
        self._report.set_index(['build_id', 'testsuite', 'testcase']).to_json(self._output_file)

    def _append_pass(self, report):
        names = self.mangle_test_address(report.nodeid)
        testsuite = ".".join(names[:-1])
        testcase = names[-1]

        self._report = self._report.append({
            'build_id': self._build_id,
            'testsuite': testsuite,
            'testcase': testcase,
            'status': 'PASSED',
            'error': '',
            'timesamp': self.suite_start_time
        }, ignore_index=True)

    def _append_failure(self, report):
        names = self.mangle_test_address(report.nodeid)
        testsuite = ".".join(names[:-1])
        testcase = names[-1]

        if hasattr(report.longrepr, "reprcrash"):
            message = report.longrepr.reprcrash.message
        else:
            message = str(report.longrepr)

        self._report = self._report.append({
            'build_id': self._build_id,
            'testsuite': testsuite,
            'testcase': testcase,
            'status': 'FAILED',
            'error': message,
            'timesamp': self.suite_start_time
        }, ignore_index=True)

    def _append_skipped(self, report):
        pass  # TODO: how should we deal with skipped tests?

    # Pytest hooks

    def pytest_runtest_logreport(self, report):
        if report.passed:
            if report.when == 'call':
                self._append_pass(report)
                self._build_id += 1  # TODO: save only once
        elif report.failed:
            if report.when == 'teardown':
                pass  # TODO: save teardown errors
            elif report.when == 'call':
                self._append_failure(report)
                self._build_id += 1  # TODO: save only once
        elif report.skipped:
            self._append_skipped(report)

    def pytest_collectreport(self, report):
        if report.failed:
            self._append_failed(report)

    def pytest_sessionstart(self, session):
        self.suite_start_time = time.time()

    def pytest_sessionfinish(self, session):
        self._save_report()
        pass


def pytest_addoption(parser):
    group = parser.getgroup('terminal reporting')
    group.addoption('--pandas', action='store', dest='pandaspath',
                    metavar='path', default=None,
                    help='create Pandas dataframe report file at given path.')


def pytest_configure(config):
    logging.config.fileConfig('logging.conf')

    output = config.getoption('pandaspath')
    config._pandas = PandasReport(output)
    config.pluginmanager.register(config._pandas)
