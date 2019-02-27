import logging
import logging.config

logging.config.fileConfig('logging.conf')
logger = logging.getLogger(__name__)

import os # NOQA E402
import time # NOQA E402
from shakedown.clients import node # NOQA E402


def main(download_dir):
    client = node.Node()

    bundle = client.diagnostics.create()

    while bundle.download_path() is None:
        logger.info('Diagnostic bundle is not complete yet. Waiting another 10 seconds.')
        time.sleep(10)

    bundle.download(download_dir, bundle.download_path())


if __name__ == "__main__":
    download_path = os.path.join(os.getcwd(), 'diagnostics.zip')
    main(download_path)
