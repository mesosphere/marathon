
import contextlib
from dcos import config


@contextlib.contextmanager
def dcos_config():
    """ Context manager for altering the toml
    """

    toml_config_o = config.get_config()
    try:
        yield
    finally:
        # return config to previous state
        config.save(toml_config_o)
