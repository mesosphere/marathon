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

HAPROXY_HTTP_FRONTEND_ACL = '''  acl host_{0} hdr(host) -i {1}
  use_backend {2} if host_{0}
'''

HAPROXY_FRONTEND_HEAD = '''
frontend {0}
  bind *:{1}
'''

HAPROXY_BACKEND_HEAD = '''
backend {0}
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
    listener = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

    frontends += HAPROXY_FRONTEND_HEAD.format(listener, app.servicePort)

    backends += HAPROXY_BACKEND_HEAD.format(listener)

    # if it's a HTTP service
    if app.hostname:
      cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)
      http_frontends += HAPROXY_HTTP_FRONTEND_ACL.format(cleanedUpHostname, app.hostname, listener)
      frontends += "  mode http\n"
      backends += HAPROXY_BACKEND_HTTP_OPTIONS
    else:
      frontends += "  mode tcp\n"

    frontends += "  use_backend {0}\n".format(listener)

    for backend in sorted(app.backends, key = attrgetter('host', 'port')):
      backends += "  server {0}:{1}\n".format(backend.host, backend.port)


  config += http_frontends
  config += frontends
  config += backends

  return config

print config(apps)
