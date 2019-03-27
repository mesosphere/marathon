import logging
import logging.config

pytest_plugins = ["pytest-dcos"]


def pytest_configure(config):
    logging.config.fileConfig('logging.conf')
