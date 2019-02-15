import logging
import logging.config

logging.config.fileConfig('logging.conf')
logger = logging.getLogger(__name__)

import os
import time
from shakedown.clients import system

def main(download_dir):
    client = system.System()

    bundle = client.diagnostics.create()

    while bundle.status() is None:
        logger.info('Diagnostic bundle is not complete yet. Waiting another 10 seconds.')
        time.sleep(10)

    bundle.download(download_dir)

if __name__ == "__main__":
    download_path = os.path.join(os.getcwd(), 'diagnostics.zip')
    main(download_path)