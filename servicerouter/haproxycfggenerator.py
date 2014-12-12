#!/usr/bin/env python
import re
import sys
import os.path
import logging
from logging.handlers import SysLogHandler
from subprocess import call
from operator import attrgetter
from tempfile import mkstemp
from shutil import move

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
syslogHandler.setLevel(logging.ERROR)
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

class MarathonBackend(object):
  def __init__(self, host, port):
    self.host = host
    self.port = port

class MarathonApp(object):
  def __init__(self, appId, hostname, servicePort, backends, sticky=False, redirectHttpToHttps=False, sslCert=None, bindAddr='*'):
    self.appId = appId
    self.hostname = hostname
    self.servicePort = servicePort
    self.backends = backends
    self.sticky = sticky
    self.redirectHttpToHttps = redirectHttpToHttps
    self.sslCert = sslCert
    self.bindAddr = bindAddr

apps = [
  MarathonApp('/mm/application/portal', 'app.mesosphere.com', 9000, [
      MarathonBackend('srv3.hw.ca1.mesosphere.com', 31006),
      MarathonBackend('srv2.hw.ca1.mesosphere.com', 31671),
      MarathonBackend('srv2.hw.ca1.mesosphere.com', 31030),
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31006)
    ], True, True, None, '127.0.0.1'),
  MarathonApp('/mm/service/collector', 'collector.mesosphere.com', 7070, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31005)
    ], True, True, '/etc/ssl/certs/mesosphere.pem'),
  MarathonApp('/mm/service/collector', 'collector2.mesosphere.com', 9990, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31006)
    ]),
  MarathonApp('/some/generic/tcp/app', '', 3306, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31632)
    ])
  ]

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

    backends += HAPROXY_BACKEND_HEAD.format(backend=backend)

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

#compareWriteAndReloadConfig(config(apps), HAPROXY_CONFIG)
