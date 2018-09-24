import logging
import logging.config


def pytest_configure(config):
    logging.config.fileConfig('logging.conf')
