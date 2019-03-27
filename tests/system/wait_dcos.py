import logging
import logging.config

logging.config.fileConfig('logging.conf')
logger = logging.getLogger(__name__)

import os # NOQA E402
from dcos_test_utils.dcos_api import DcosApiSession
from dcos_test_utils.enterprise import EnterpriseApiSession


def main(ee_flag):
    if ee_flag:
        dcos_api_session = EnterpriseApiSession.create()
    else:
        dcos_api_session = DcosApiSession.create()

    dcos_api_session.wait_for_dcos()

if __name__ == "__main__":
    ee = os.environ.get('DCOS_ENTERPRISE', 'false') == 'true'
    main(ee)
