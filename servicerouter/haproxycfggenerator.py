#!/usr/bin/env python
import re
from operator import attrgetter

HAPROXY_HEAD = '''global
  daemon
  log 127.0.0.1 local0
  log 127.0.0.1 local1 notice
  maxconn 4096

defaults
  log            global
  retries             3
  maxconn          2000
  timeout connect  5000
  timeout client  50000
  timeout server  50000

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
  bind *:{servicePort}
  mode {mode}
'''

HAPROXY_BACKEND_HEAD = '''
backend {backend}
  balance roundrobin
'''

HAPROXY_BACKEND_HTTP_OPTIONS = '''  option forwardfor
  http-request set-header X-Forwarded-Port %[dst_port]
  http-request add-header X-Forwarded-Proto https if { ssl_fc }
'''


class MarathonBackend(object):
  def __init__(self, host, port):
    self.host = host
    self.port = port

class MarathonApp(object):
  def __init__(self, appId, hostname, servicePort, backends):
    self.appId = appId
    self.hostname = hostname
    self.servicePort = servicePort
    self.backends = backends

apps = [
  MarathonApp('/mm/application/portal', 'app.mesosphere.com', 9000, [
      MarathonBackend('srv3.hw.ca1.mesosphere.com', 31006),
      MarathonBackend('srv2.hw.ca1.mesosphere.com', 31671),
      MarathonBackend('srv2.hw.ca1.mesosphere.com', 31030),
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31006)
    ]),
  MarathonApp('/mm/service/collector', 'collector.mesosphere.com', 7070, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31005)
    ]),
  MarathonApp('/mm/service/collector', 'collector2.mesosphere.com', 9990, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31006)
    ]),
  MarathonApp('/some/generic/tcp/app', '', 3306, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31632)
    ])
  ]

def config(apps):
  config = HAPROXY_HEAD

  http_frontends = HAPROXY_HTTP_FRONTEND_HEAD
  frontends = str()
  backends = str()

  for app in sorted(apps, key = attrgetter('appId', 'servicePort')):
    backend = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

    frontends += HAPROXY_FRONTEND_HEAD.format(
        backend = backend,
        servicePort = app.servicePort,
        mode = 'http' if app.hostname else 'tcp'
      )

    backends += HAPROXY_BACKEND_HEAD.format(backend = backend)

    # if a hostname is set we add the app to the vhost section
    # of our haproxy config
    if app.hostname:
      cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)
      http_frontends += HAPROXY_HTTP_FRONTEND_ACL.format(
          cleanedUpHostname = cleanedUpHostname,
          hostname = app.hostname,
          backend = backend
        )
      backends += HAPROXY_BACKEND_HTTP_OPTIONS

    frontends += "  use_backend {backend}\n".format(backend = backend)

    for backendServer in sorted(app.backends, key = attrgetter('host', 'port')):
      backends += "  server {host}:{port}\n".format(
          host = backendServer.host,
          port = backendServer.port
        )


  config += http_frontends
  config += frontends
  config += backends

  return config

print config(apps)
