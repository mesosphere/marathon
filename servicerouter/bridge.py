#!/usr/bin/env python2

"""Updates haproxy config based on marathon.

Features:
  - Virtual Host aliases for services
  - Soft restart of haproxy
  - SSL Termination / floating IPs
  - Live update from marathon event bus


Usage:
1. As a daemon (Must be accessible at a fixed ip/port for events from marathon)

2. Periodic runs via CRON (classic haproxy-marathon-bridge)

HA Usage:
  Run multiples instances via marathon. Unique host constraint. node name is the task id?


Configuration:
Primary source of data is marathon.
Make a list offline for the strongly  named / vhosts you want to forward
TODO: Base config for haproxy which is used as a template


Operation:
  Users must remove remove callback urls themselves. Otherwise marathon will
  forever ping nothing important
  Generates a new config file in /tmp
  Moves it to /etc/haproxy/{timestamp}

TODO:
  More reliable way to ping haproxy
  Ping apps endpoint to get environment variables to determine things like hostname to use
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
from subprocess import call
import sys
from tempfile import mkstemp
from wsgiref.simple_server import make_server


if sys.platform == "darwin":
  syslogSocket = "/var/run/syslog"
else:
  syslogSocket = "/dev/log"

logger = logging.getLogger('haproxycfggenerator')
logger.setLevel(logging.DEBUG)

syslogHandler = SysLogHandler(syslogSocket)
consoleHandler = logging.StreamHandler()
formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
syslogHandler.setFormatter(formatter)
consoleHandler.setFormatter(formatter)
#syslogHandler.setLevel(logging.ERROR)
logger.addHandler(syslogHandler)
logger.addHandler(consoleHandler)

HAPROXY_CONFIG = '/etc/haproxy/haproxy.cfg'

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
        sslCertOptions=' ssl crt '+app.sslCert if app.sslCert else ''
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
    # TODO(lukas): Check if the hostname is already defined by another service
    if app.hostname:
      logger.debug("adding virtual host for app with hostname %s", app.hostname)
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
      logger.debug("backend server at %s:%d", backendServer.host, backendServer.port)
      serverName = re.sub(r'[^a-zA-Z0-9\-]', '_', backendServer.host+'_'+str(backendServer.port))
      backends += HAPROXY_BACKEND_SERVER_OPTIONS.format(
          host=backendServer.host,
          port=backendServer.port,
          serverName=serverName,
          cookieOptions=' check cookie '+serverName if app.sticky else ''
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
  elif ( os.path.isfile('/usr/lib/systemd/system/haproxy.service')
      or os.path.isfile('/etc/systemd/system/haproxy.service')
    ):
    logger.debug("we seem to be running on systemd based system")
    reloadCommand = ['systemctl', 'reload', 'haproxy']
  else:
    logger.debug("we seem to be running on a sysvinit based system")
    reloadCommand = ['/etc/init.d/haproxy', 'reload']

  logger.info("reloading using %s", " ".join(reloadCommand))
  return call(reloadCommand)

def writeConfig(config, configFile):
  fd, haproxyTempConfigFile = mkstemp()
  logger.debug("writing config to temp file %s", haproxyTempConfigFile)
  haproxyTempConfig = os.fdopen(fd, 'w')
  haproxyTempConfig.write(config)
  haproxyTempConfig.close()
  logger.debug("moving temp file %s to %s", haproxyTempConfigFile, HAPROXY_CONFIG)
  move(haproxyTempConfigFile, HAPROXY_CONFIG)

def compareWriteAndReloadConfig(config, configFile):
  runningConfig = str()
  try:
    logger.debug("reading running config from %s", configFile)
    with open(configFile, "r") as configFile:
      runningConfig = configFile.read()
  except IOError:
    logger.warning("couldn't open config file for reading")

  if runningConfig != config:
    logger.info("running config is different from generated config - reloading")
    writeConfig(config, configFile)
    reloadConfig()


# TODO (cmaloney): Merge with lukas' definitions
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

    #port -> MarathonService
    self.services = dict()

  def __hash__(self):
    return hash(self.appId)

  def __eq__(self, other):
    return self.appId == other.appId


class Marathon(object):
  def __init__(self, hosts):
    self.__hosts = hosts

  def api_req_raw(self, method, path, body=None, **kwargs):
    for host in self.__hosts:
      path_str = os.path.join(host, 'v2')
      if len(path) == 2:
        # TODO(cmaloney): Hack to join with appIds properly
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

    print response.content
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

def set_hostname(x, y):
    x.hostname = y

def set_sticky(x, y):
    x.sticky = y

def redirectHttpToHttps(x, y):
    x.redirectHttpToHttps = y

def sslCert(x, y):
    x.sslCert = y

def bindAddr(x, y):
    x.bindAddr = y

env_keys = {
  'HAPROXY_VHOST{0}': set_hostname,
  'HAPROXY_VHOST{0}_STICKY': set_sticky,
  'HAPROXY_VHOST{0}_REDIRECT_TO_HTTPS': redirectHttpToHttps,
  'HAPROXY_VHOST{0}_SSL_CERT': sslCert,
  'HAPROXY_VHOST{0}_BIND_ADDR': bindAddr
}


class MarathonEventSubscriber(object):
  def __init__(self, marathon, addr):
    self.__marathon = marathon
    #NOTE: Convert to a list before handing to haproxycfggenerator
    #appId -> MarathonApp
    self.__apps = dict()
    marathon.add_subscriber(addr)

    # Fetch the base data
    self.reset_from_tasks()

  def reset_from_tasks(self):
    tasks = self.__marathon.tasks()

    self.__apps = dict()

    for task in tasks:
      # For each task, extract self.__apps
      # and the backends within each app.
      for i in xrange(len(task['servicePorts'])):
        servicePort = task['servicePorts'][i]
        port = task['ports'][i]
        appId = task['appId']

        if appId not in self.__apps:
          self.__apps[appId] = MarathonApp(self.__marathon, appId)

        app = self.__apps[appId]
        if servicePort not in app.services:
          app.services[servicePort] = MarathonService(appId, servicePort)

        service = app.services[servicePort]

        # Environment variable based config
        #TODO(cmaloney): Move to labels once those are supported throughout the stack
        for key_unformatted in env_keys:
          key = key_unformatted.format(i)
          if key in app.app[u'env']:
              func = env_keys[key_unformatted]
              func(service, app.app[u'env'][key])

        service.add_backend(task['host'], port)

    # Convert to haproxycfggenerator format
    haproxy_apps = list()
    for app in self.__apps.values():
        for service in app.services.values():
            haproxy_apps.append(service)
    #haproxycfggenerator.logger.debug(haproxycfggenerator.config(haproxy_apps))
    haproxycfggenerator.compareWriteAndReloadConfig(haproxycfggenerator.config(haproxy_apps), haproxycfggenerator.HAPROXY_CONFIG)


  def handle_event(self, event):
    if event['eventType'] == 'status_update_event':
      #TODO (cmaloney): Handle events more intelligently so that we add/remove things well
      self.reset_from_tasks()


# TODO(cmaloney): Switch to a sane http server
# TODO(cmaloney): Good exception catching, etc
def wsgi_app(env, start_response):
  subscriber.handle_event(json.load(env['wsgi.input']))
  #TODO(cmaloney): Make this have a simple useful webui for debugging / monitoring
  start_response('200 OK', [('Content-Type', 'text/html')])

  return "Got it"


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Marathon HAProxy Service Router")
    parser.add_argument("--marathon_endpoints", "-m", required=True, nargs="+",
                        help="Marathon endpoint, eg. http://marathon1,http://marathon2:8080")
    parser.add_argument("--callback_url", "-c",
                        help="Marathon callback URL")
    args = parser.parse_args()

    marathon = Marathon(args.marathon_endpoints)
    subscriber = MarathonEventSubscriber(marathon, args.callback_url)

    print "Serving on port 8000..."
    httpd = make_server('', 8000, wsgi_app)
    httpd.serve_forever()
