#!/usr/bin/env python
import re
import os.path
from subprocess import call
from operator import attrgetter
from tempfile import mkstemp
from shutil import move

HAPROXY_CONFIG = '/Users/lukas/haproxy.cfg'

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
  print "generating config"
  config = HAPROXY_HEAD

  http_frontends = HAPROXY_HTTP_FRONTEND_HEAD
  frontends = str()
  backends = str()

  for app in sorted(apps, key=attrgetter('appId', 'servicePort')):
    backend = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

    frontends += HAPROXY_FRONTEND_HEAD.format(
        bindAddr=app.bindAddr,
        backend=backend,
        servicePort=app.servicePort,
        mode='http' if app.hostname else 'tcp',
        sslCertOptions=' ssl crt '+app.sslCert if app.sslCert else ''
      )

    if app.redirectHttpToHttps:
      frontends += HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS


    backends += HAPROXY_BACKEND_HEAD.format(backend=backend)

    # if a hostname is set we add the app to the vhost section
    # of our haproxy config
    # TODO(lukas): Check if the hostname is already defined by another service
    if app.hostname:
      cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)
      http_frontends += HAPROXY_HTTP_FRONTEND_ACL.format(
          cleanedUpHostname=cleanedUpHostname,
          hostname=app.hostname,
          backend=backend
        )
      backends += HAPROXY_BACKEND_HTTP_OPTIONS

    if app.sticky:
      backends += HAPROXY_BACKEND_STICKY_OPTIONS

    frontends += HAPROXY_FRONTEND_BACKEND_GLUE.format(backend=backend)

    for backendServer in sorted(app.backends, key=attrgetter('host', 'port')):
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
  print "reloading"
  if os.path.isfile('/etc/init/haproxy.conf'):
    reloadCommand = ['reload', 'haproxy']
  elif ( os.path.isfile('/usr/lib/systemd/system/haproxy.service')
      or os.path.isfile('/etc/systemd/system/haproxy.service')
    ):
    reloadCommand = ['systemctl', 'reload', 'haproxy']
  else:
    reloadCommand = ['/etc/init.d/haproxy', 'reload']

  return call(reloadCommand)

def writeConfig(config, configFile):
  print "writing config"
  fd, haproxyTempConfigFile = mkstemp()
  haproxyTempConfig = os.fdopen(fd, 'w')
  haproxyTempConfig.write(config)
  haproxyTempConfig.close()
  move(haproxyTempConfigFile, HAPROXY_CONFIG)

def compareWriteAndReloadConfig(config, configFile):
  runningConfig = str()
  try:
    with open(configFile, "r") as configFile:
      runningConfig = configFile.read()
  except IOError:
    print "couldn't open config file for reading"

  if runningConfig != config:
    writeConfig(config, configFile)
    reloadConfig()

compareWriteAndReloadConfig(config(apps), HAPROXY_CONFIG)
