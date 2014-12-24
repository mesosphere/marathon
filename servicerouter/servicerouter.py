#!/usr/bin/env python2

"""Updates haproxy config based on marathon.

Features:
  - Virtual Host aliases for services
  - Soft restart of haproxy
  - SSL Termination
  - (Optional): real-time update from marathon events

Usage:
./servicerouter.py --listening http://192.168.0.1:4002 -m http://marathon1
./servicerouter.py -m http://marathon1 -m http://marathon2:8080 -m http://marathon3:8080

HA Usage:
  TODO (Run on multiple hosts, send traffic to all)

Configuration:
  Service configuration lives in marathon via environment variables.
  The servicerouter just needs to know where to find marathon.
  To run in listening mode you must also specify the address + port at
  which the servicerouter can be reached by marathon.

  Every service port in marathon can be configured independently.

  Environment Variables:
    HAPROXY_{n}_VHOST
      HTTP Virtual Host proxy hostname to catch
      Ex: HAPROXY_0_VHOST = 'marathon.mesosphere.com'

    HAPROXY_{n}_STICKY
      Use sticky request routing for the service
      Ex: HAPROXY_0_STICKY = true

    HAPROXY_{n}_REDIRECT_TO_HTTPS
      Redirect HTTP traffic to https
      Ex: HAPROXY_0_REDIRECT_TO_HTTPS = true

    HAPROXY_{n}_SSL_CERT
      Use the given SSL cert for TLS/SSL traffic
      Ex: HAPROXY_0_SSL_CERT = '/etc/ssl/certs/marathon.mesosphere.com'

    HAPROXY_{n}_BIND_ADDR
      Bind to the specific address for the service
      Ex: HAPROXY_0_BIND_ADDR = '10.0.0.42'

Operational  Notes:
  - When a node in listening mode fails, remove the callback url for that
    node in marathon by hand.

TODO:
  More reliable way to ping, restart haproxy (Install the right reloader)
"""

import argparse
import json
import logging
from logging.handlers import SysLogHandler
from operator import attrgetter
import os.path
import requests
import re
from shutil import move
import subprocess
import sys
from tempfile import mkstemp
from wsgiref.simple_server import make_server

HAPROXY_HEAD = '''global
  daemon
  log 127.0.0.1 local0
  log 127.0.0.1 local1 notice
  maxconn 4096

defaults
  log            global
  retries             3
  maxconn            2s
  timeout connect    5s
  timeout client    50s
  timeout server    50s

listen stats
  bind 127.0.0.1:9090
  balance
  mode http
  stats enable
  stats auth admin:admin
'''

HAPROXY_HTTP_FRONTEND_HEAD = '''
frontend marathon_http_in
  bind *:80
  mode http
'''

HAPROXY_HTTP_FRONTEND_ACL = '''  acl host_{cleanedUpHostname} hdr(host) -i {hostname}
  use_backend {backend} if host_{cleanedUpHostname}
'''

HAPROXY_FRONTEND_HEAD = '''
frontend {backend}
  bind {bindAddr}:{servicePort}{sslCertOptions}
  mode {mode}
'''

HAPROXY_FRONTEND_BACKEND_GLUE = '''  use_backend {backend}
'''

HAPROXY_BACKEND_HEAD = '''
backend {backend}
  balance roundrobin
  mode {mode}
'''

HAPROXY_BACKEND_HTTP_OPTIONS = '''  option forwardfor
  http-request set-header X-Forwarded-Port %[dst_port]
  http-request add-header X-Forwarded-Proto https if { ssl_fc }
'''

HAPROXY_BACKEND_STICKY_OPTIONS = '''  cookie mesosphere_server_id insert indirect nocache
'''

HAPROXY_BACKEND_SERVER_OPTIONS = '''  server {serverName} {host}:{port}{cookieOptions}
'''

HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS = '''  redirect scheme https if !{ ssl_fc }
'''


def string_to_bool(s):
  return s.lower() in ["true", "t", "yes", "y"]


def set_hostname(x, y):
    x.hostname = y


def set_sticky(x, y):
    x.sticky = string_to_bool(y)


def redirect_http_to_https(x, y):
    x.redirectHttpToHttps = string_to_bool(y)


def sslCert(x, y):
    x.sslCert = y


def bindAddr(x, y):
    x.bindAddr = y

env_keys = {
    'HAPROXY_{0}_VHOST': set_hostname,
    'HAPROXY_{0}_STICKY': set_sticky,
    'HAPROXY_{0}_REDIRECT_TO_HTTPS': redirect_http_to_https,
    'HAPROXY_{0}_SSL_CERT': sslCert,
    'HAPROXY_{0}_BIND_ADDR': bindAddr
}

logger = logging.getLogger('haproxycfggenerator')


class MarathonBackend(object):

    def __init__(self, host, port):
        self.host = host
        self.port = port

    def __hash__(self):
        return hash((self.host, self.port))


class MarathonService(object):

    def __init__(self, appId, servicePort):
        self.appId = appId
        self.servicePort = servicePort
        self.backends = set()
        self.hostname = None
        self.sticky = False
        self.redirectHttpToHttps = False
        self.sslCert = None
        self.bindAddr = '*'

    def add_backend(self, host, port):
        self.backends.add(MarathonBackend(host, port))

    def __hash__(self):
        return hash(self.servicePort)

    def __eq__(self, other):
        return self.servicePort == other.servicePort


class MarathonApp(object):

    def __init__(self, marathon, appId):
        self.app = marathon.get_app(appId)
        self.appId = appId

        # port -> MarathonService
        self.services = dict()

    def __hash__(self):
        return hash(self.appId)

    def __eq__(self, other):
        return self.appId == other.appId


class Marathon(object):

    def __init__(self, hosts):
        # TODO(cmaloney): Support getting master list from zookeeper
        self.__hosts = hosts

    def api_req_raw(self, method, path, body=None, **kwargs):
        for host in self.__hosts:
            path_str = os.path.join(host, 'v2')
            if len(path) == 2:
                assert(path[0] == 'apps')
                path_str += '/apps/{0}'.format(path[1])
            else:
                path_str += '/' + path[0]
            response = requests.request(
                method,
                path_str,
                headers={
                    'Accept': 'application/json',
                    'Content-Type': 'application/json'
                },
                **kwargs
            )
            if response.status_code == 200:
                break

        response.raise_for_status()
        return response

    def api_req(self, method, path, **kwargs):
        return self.api_req_raw(method, path, **kwargs).json()

    def create(self, app_json):
        return self.api_req('POST', ['apps'], app_json)

    def get_app(self, appid):
        return self.api_req('GET', ['apps', appid])["app"]

    # Lists all running apps.
    def list(self):
        return self.api_req('GET', ['apps'])["apps"]

    def tasks(self):
        return self.api_req('GET', ['tasks'])["tasks"]

    def add_subscriber(self, callbackUrl):
        return self.api_req('POST', ['eventSubscriptions'], params={'callbackUrl': callbackUrl})

    def remove_subscriber(self, callbackUrl):
        return self.api_req('DELETE', ['eventSubscriptions'], params={'callbackUrl': callbackUrl})


def config(apps):
    logger.info("generating config")
    config = HAPROXY_HEAD

    http_frontends = HAPROXY_HTTP_FRONTEND_HEAD
    frontends = str()
    backends = str()

    for app in sorted(apps, key=attrgetter('appId', 'servicePort')):
        logger.debug("configuring app %s", app.appId)
        backend = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

        logger.debug("frontend at %s:%d with backend %s",
                     app.bindAddr, app.servicePort, backend)
        frontends += HAPROXY_FRONTEND_HEAD.format(
            bindAddr=app.bindAddr,
            backend=backend,
            servicePort=app.servicePort,
            mode='http' if app.hostname else 'tcp',
            sslCertOptions=' ssl crt ' + app.sslCert if app.sslCert else ''
        )

        if app.redirectHttpToHttps:
            logger.debug("rule to redirect http to https traffic")
            frontends += HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS

        backends += HAPROXY_BACKEND_HEAD.format(
            backend=backend,
            mode='http' if app.hostname else 'tcp'
        )

        # if a hostname is set we add the app to the vhost section
        # of our haproxy config
        # TODO(lukas): Check if the hostname is already defined by another
        # service
        if app.hostname:
            logger.debug(
                "adding virtual host for app with hostname %s", app.hostname)
            cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)
            http_frontends += HAPROXY_HTTP_FRONTEND_ACL.format(
                cleanedUpHostname=cleanedUpHostname,
                hostname=app.hostname,
                backend=backend
            )
            backends += HAPROXY_BACKEND_HTTP_OPTIONS

        if app.sticky:
            logger.debug("turning on sticky sessions")
            backends += HAPROXY_BACKEND_STICKY_OPTIONS

        frontends += HAPROXY_FRONTEND_BACKEND_GLUE.format(backend=backend)

        for backendServer in sorted(app.backends, key=attrgetter('host', 'port')):
            logger.debug(
                "backend server at %s:%d", backendServer.host, backendServer.port)
            serverName = re.sub(
                r'[^a-zA-Z0-9\-]', '_', backendServer.host + '_' + str(backendServer.port))
            backends += HAPROXY_BACKEND_SERVER_OPTIONS.format(
                host=backendServer.host,
                port=backendServer.port,
                serverName=serverName,
                cookieOptions=' check cookie ' +
                serverName if app.sticky else ''
            )

    config += http_frontends
    config += frontends
    config += backends

    return config


def reloadConfig():
    logger.debug("trying to find out how to reload the configuration")
    if os.path.isfile('/etc/init/haproxy.conf'):
        logger.debug("we seem to be running on an Upstart based system")
        reloadCommand = ['reload', 'haproxy']
    elif (os.path.isfile('/usr/lib/systemd/system/haproxy.service')
          or os.path.isfile('/etc/systemd/system/haproxy.service')
          ):
        logger.debug("we seem to be running on systemd based system")
        reloadCommand = ['systemctl', 'reload', 'haproxy']
    else:
        logger.debug("we seem to be running on a sysvinit based system")
        reloadCommand = ['/etc/init.d/haproxy', 'reload']

    logger.info("reloading using %s", " ".join(reloadCommand))
    try:
        subprocess.check_call(reloadCommand)
    except OSError as ex:
        logger.error("unable to reload config using command %s", " ".join(reloadCommand))
        logger.error("OSError: %s", ex)
    except subprocess.CalledProcessError as ex:
        logger.error("unable to reload config using command %s", " ".join(reloadCommand))
        logger.error("reload returned non-zero: %s", ex)


def writeConfig(config, config_file):
    # Write config to a temporary location
    fd, haproxyTempConfigFile = mkstemp()
    logger.debug("writing config to temp file %s", haproxyTempConfigFile)
    with os.fdopen(fd, 'w') as haproxyTempConfig:
      haproxyTempConfig.write(config)

    # Move into place
    logger.debug("moving temp file %s to %s", haproxyTempConfigFile, config_file)
    move(haproxyTempConfigFile, config_file)


def compareWriteAndReloadConfig(config, config_file):
    # See if the last config on disk matches this, and if so don't reload
    # haproxy
    runningConfig = str()
    try:
        logger.debug("reading running config from %s", config_file)
        with open(config_file, "r") as f:
            runningConfig = f.read()
    except IOError:
        logger.warning("couldn't open config file for reading")

    if runningConfig != config:
        logger.info(
            "running config is different from generated config - reloading")
        writeConfig(config, config_file)
        reloadConfig()


def get_apps(marathon):
    tasks = marathon.tasks()

    apps = dict()

    for task in tasks:
        # For each task, extract the app it belongs to and add a
        # backend for each service it provides
        if not 'servicePorts' in task:
          continue

        for i in xrange(len(task['servicePorts'])):
            servicePort = task['servicePorts'][i]
            port = task['ports'][i]
            appId = task['appId']

            if appId not in apps:
                apps[appId] = MarathonApp(marathon, appId)

            app = apps[appId]
            if servicePort not in app.services:
                app.services[servicePort] = MarathonService(
                    appId, servicePort)

            service = app.services[servicePort]

            # Load environment variable configuration
            # TODO(cmaloney): Move to labels once those are supported
            # throughout the stack
            for key_unformatted in env_keys:
                key = key_unformatted.format(i)
                if key in app.app[u'env']:
                    func = env_keys[key_unformatted]
                    func(service, app.app[u'env'][key])

            service.add_backend(task['host'], port)

    # Convert into a list for easier consumption
    apps_list = list()
    for app in apps.values():
        for service in app.services.values():
            apps_list.append(service)
    return apps_list


def regenerate_config(apps, config_file):
  compareWriteAndReloadConfig(config(apps), config_file)


class MarathonEventSubscriber(object):

    def __init__(self, marathon, addr, config_file):
        marathon.add_subscriber(addr)
        self.__marathon = marathon
        # appId -> MarathonApp
        self.__apps = dict()
        self.__config_file = config_file

        # Fetch the base data
        self.reset_from_tasks()

    def reset_from_tasks(self):
      self.__apps = get_apps(self.__marathon)
      regenerate_config(self.__apps, self.__config_file)

    def handle_event(self, event):
        if event['eventType'] == 'status_update_event':
            # TODO (cmaloney): Handle events more intelligently so we don't
            # unnecessarily hammer the Marathon API.
            self.reset_from_tasks()


def get_arg_parser():
    parser = argparse.ArgumentParser(
        description="Marathon HAProxy Service Router")
    parser.add_argument("--marathon", "-m",
                        required=True,
                        nargs="+",
                        help="Marathon endpoint, eg. -m http://marathon1:8080 -m http://marathon2:8080")
    parser.add_argument("--listening", "-l",
                        help="The HTTP address that marathon can call this script back at (http://lb1:8080)")
    parser.add_argument("--syslog-socket",
                        help="Socket to write syslog messages to",
                        default="/var/run/syslog" if sys.platform == "darwin" else "/dev/log"
                        )
    parser.add_argument("--haproxy-config",
                        help="Location of haproxy configuration",
                        default="/etc/haproxy/haproxy.cfg"
                        )
    return parser


def run_server(marathon, callback_url, config_file):
    subscriber = MarathonEventSubscriber(marathon, callback_url, config_file)

    # TODO(cmaloney): Switch to a sane http server
    # TODO(cmaloney): Good exception catching, etc
    def wsgi_app(env, start_response):
        subscriber.handle_event(json.load(env['wsgi.input']))
        # TODO(cmaloney): Make this have a simple useful webui for debugging /
        # monitoring
        start_response('200 OK', [('Content-Type', 'text/html')])

        return "Got it"

    print "Serving on port 8000..."
    httpd = make_server('', 8000, wsgi_app)
    httpd.serve_forever()


def setup_logging(syslog_socket):
    logger.setLevel(logging.DEBUG)

    syslogHandler = SysLogHandler(args.syslog_socket)
    consoleHandler = logging.StreamHandler()
    formatter = logging.Formatter(
        '%(name)s: %(message)s')
    syslogHandler.setFormatter(formatter)
    consoleHandler.setFormatter(formatter)
    # syslogHandler.setLevel(logging.ERROR)
    logger.addHandler(syslogHandler)
    logger.addHandler(consoleHandler)


if __name__ == '__main__':
    # Process arguments
    args = get_arg_parser().parse_args()

    #Setup logging
    setup_logging(args.syslog_socket)

    # Marathon API connector
    marathon = Marathon(args.marathon)

    # If in listening mode, spawn a webserver waiting for events. Otherwise just write the config
    if args.listening:
      run_server(marathon, args.listening, args.haproxy_config)
    else:
      # Generate base config
      regenerate_config(get_apps(marathon), args.haproxy_config)
