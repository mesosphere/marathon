#!/usr/bin/env python

import logging
import os
import platform
import signal
import socket
import sys
import threading

# Ensure compatibility with Python 2 and 3.
# See https://github.com/JioCloud/python-six/blob/master/six.py for details.
PY2 = sys.version_info[0] == 2
PY3 = sys.version_info[0] == 3

if PY2:
    from SimpleHTTPServer import SimpleHTTPRequestHandler
    from SocketServer import TCPServer as HTTPServer
    from urllib2 import Request, urlopen
else:
    from http.server import SimpleHTTPRequestHandler
    from http.server import HTTPServer
    from urllib.request import Request, urlopen

if PY2:
    byte_type = unicode

    def response_status(response):
        return response.getcode()

else:
    byte_type = bytes

    def response_status(response):
        return response.getcode()


def make_handler(app_id, version, task_id, base_url):
    """
    Factory method that creates a handler class.
    """

    class Handler(SimpleHTTPRequestHandler):

        def handle_ping(self):
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            msg = "Pong {}".format(app_id)

            self.wfile.write(byte_type(msg, "UTF-8"))

        def check_readiness(self):

            url = "{}/{}/ready".format(base_url, task_id)

            logging.debug("Query %s for readiness", url)
            url_req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
            response = urlopen(url_req)
            res = response.read()
            status = response_status(response)
            logging.debug("Current readiness is %s, %s", res, status)

            self.send_response(status)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            self.wfile.write(res)

            logging.debug("Done processing readiness request.")
            return

        def check_health(self):

            url = "{}/health".format(base_url)

            logging.debug("Query %s for health", url)
            url_req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
            response = urlopen(url_req)
            res = response.read()
            status = response_status(response)
            logging.debug("Current health is %s, %s", res, status)

            self.send_response(status)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            self.wfile.write(res)

            logging.debug("Done processing health request.")
            return

        def do_GET(self):
            try:
                logging.debug("Got GET request")
                if self.path == '/ping':
                    return self.handle_ping()
                elif self.path == '/ready':
                    return self.check_readiness()
                else:
                    return self.check_health()
            except Exception:
                logging.exception('Could not handle GET request')

        def do_POST(self):
            try:
                logging.debug("Got POST request")
                return self.check_health()
            except Exception:
                logging.exception('Could not handle POST request')

    return Handler

if __name__ == "__main__":
    logging.basicConfig(
        format='%(asctime)s %(levelname)-8s: %(message)s',
        level=logging.DEBUG)
    logging.info(platform.python_version())
    logging.debug(sys.argv)

    port = int(sys.argv[1])
    app_id = sys.argv[2]
    version = sys.argv[3]
    base_url = sys.argv[4]
    task_id = os.getenv("MESOS_TASK_ID", "<UNKNOWN>")

    HTTPServer.allow_reuse_address = True
    try:
        httpd = HTTPServer(("", port),
                           make_handler(app_id, version, task_id, base_url))
    except socket.error:
        logging.error("Processes bound on port %d", port)
        os.system("ps -a | grep $(lsof -ti :{})".format(port))
        raise

    msg = "AppMock[%s %s]: %s has taken the stage at port %d. "\
          "Will query %s for health and readiness status."
    logging.info(msg, app_id, version, task_id, port, base_url)

    def kill_server(server):
        logging.info("Shutting down. Waiting for last request to be handled.")
        try:
            server.shutdown()
            server.socket.close()
        except:
            logging.exception("Error in shutdown thread:")
        logging.info("Done.")

    # Trigger proper shutdown on SIGTERM.
    def handle_sigterm(signum, frame):
        # Do not set daemon = True otherwise Python will kill the shutdown
        # thread.
        shutdown_thread = threading.Thread(target=kill_server, args=(httpd, ))
        shutdown_thread.start()

    signal.signal(signal.SIGTERM, handle_sigterm)

    try:
        httpd.serve_forever()
    except:
        logging.exception("Error in server thread:")
        kill_server(httpd)
