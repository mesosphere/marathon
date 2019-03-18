import logging
import logging.config

import json
import os
import time


class PandasReport(object):
    """Reports pytest test results in a Pandas dataframe.

    This plugin roughly follows https://github.com/pytest-dev/pytest-html/blob/master/pytest_html/plugin.py.
    """

    def __init__(self, output):
        file = os.path.expanduser(os.path.expandvars(output))
        self._output_file = os.path.abspath(file)
        self._report = list()

    def _save_report(self):
        with open(self._output_file, 'w') as fp:
            json.dump(self._report, fp)

    # Pytest hooks

    def pytest_runtest_logreport(self, report):
        self._report.append({'testsuite': report.nodeid, 'testcase': 'unkown', 'status': report.outcome})

    def pytest_collectreport(self, report):
        # if report.failed:
        #    self.append_failed(report)
        pass

    def pytest_sessionstart(self, session):
        self.suite_start_time = time.time()

    def pytest_sessionfinish(self, session):
        self._save_report()
        pass


def pytest_configure(config):
    logging.config.fileConfig('logging.conf')

    output = config.getoption('pandas')
    config._pandas = PandasReport(output)
    config.pluginmanager.register(config._pandas)
