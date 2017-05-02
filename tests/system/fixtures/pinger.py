#!/usr/bin/env python

""" This app "pinger" responses to /ping with pongs and will
    response to /relay by pinging another app and respond with it's response
"""

import sys
import logging
import os
import platform
import time

# Ensure compatibility with Python 2 and 3.
# See https://github.com/JioCloud/python-six/blob/master/six.py for details.
PY2 = sys.version_info[0] == 2
PY3 = sys.version_info[0] == 3

if PY2:
    from SimpleHTTPServer import SimpleHTTPRequestHandler
    from SocketServer import TCPServer as HTTPServer
    from urllib2 import Request, urlopen
    import urlparse
else:
    from http.server import SimpleHTTPRequestHandler
    from http.server import HTTPServer
    from urllib.request import Request, urlopen
    from urllib.parse import urlparse

if PY2:
    byte_type = unicode

    def response_status(response):
        return response.getcode()

else:
    byte_type = bytes

    def response_status(response):
        return response.getcode()


def make_handler():
    """
    Factory method that creates a handler class.
    """

    class Handler(SimpleHTTPRequestHandler):

        def handle_ping(self):
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            marathonId = os.getenv("MARATHON_APP_ID", "NO_MARATHON_APP_ID_SET")
            msg = "Pong {}".format(marathonId)

            self.wfile.write(byte_type(msg, "UTF-8"))
            return

        def handle_relay(self):
            """
                provided an URL localhost:7777 or app.marathon.mesos:7777 relay will
                ping that url http://localhost:7777/ping and respond back.
                It is used for network testing in a cluster.
            """
            query = urlparse(self.path).query
            query_components = dict(qc.split("=") for qc in query.split("&"))
            logging.info(query_components)
            full_url = 'http://{}/ping'.format(query_components['url'])

            url_req = Request(full_url, headers={"User-Agent": "Mozilla/5.0"})
            response = urlopen(url_req)
            res = response.read()
            status = response_status(response)
            logging.debug("Relay request is %s, %s", res, status)

            self.send_response(status)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            self.wfile.write(res)
            marathonId = os.getenv("MARATHON_APP_ID", "NO_MARATHON_APP_ID_SET")
            msg = "\nRelay from {}".format(marathonId)
            self.wfile.write(byte_type(msg, "UTF-8"))

            return

        def do_GET(self):
            try:
                logging.debug("Got GET request")
                if self.path == '/ping':
                    return self.handle_ping()
                elif self.path.startswith('/relay-ping'):
                    return self.handle_relay()
                else:
                    return self.handle_ping()
            except:
                logging.exception('Could not handle GET request')
                raise

        def do_POST(self):
            try:
                logging.debug("Got POST request")
                return self.handle_ping()
            except:
                logging.exception('Could not handle POST request')
                raise

    return Handler


if __name__ == "__main__":
    logging.basicConfig(
        format='%(asctime)s %(levelname)-8s: %(message)s',
        level=logging.DEBUG)
    logging.info(platform.python_version())
    logging.debug(sys.argv)

    port = int(sys.argv[1])
    taskId = os.getenv("MESOS_TASK_ID", "<UNKNOWN>")

    HTTPServer.allow_reuse_address = True
    httpd = HTTPServer(("", port), make_handler())
    msg = "AppMock[%s]: has taken the stage at port %d. "
    logging.info(msg, taskId, port)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass

    logging.info("Shutting down.")
    httpd.shutdown()
    httpd.socket.close()
