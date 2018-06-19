from shakedown import *

from dcos import http

def test_http_service():
    with master_http_service():

        # this service is not externally exposed.  the http check must be from inside the cluster
        cmd = r'curl -s -o /dev/null -w "%{http_code}" http://master.mesos:7777/'
        status, output = shakedown.run_command_on_master(cmd)

        assert status
        assert output == '200'
