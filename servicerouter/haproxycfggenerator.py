#!/usr/bin/env python
import re
from operator import attrgetter

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

  config = str()
  frontends = str()
  backends = str()

  head = '''global
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

  http_frontends = '''frontend marathon_http_in
  bind *:80
  mode http
'''

  for app in sorted(apps, key = attrgetter('appId', 'servicePort')):
    listener = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

    frontends += "\nfrontend {0}\n".format(listener)
    frontends += "  bind *:{0}\n".format(app.servicePort)

    backends += "\nbackend " + listener + "\n"
    backends += "  balance roundrobin\n"

    # if it's a HTTP service
    if app.hostname:
      frontends += "  mode http\n"
      cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)
      http_frontends += "  acl host_{0} hdr(host) -i {1}\n".format(cleanedUpHostname, app.hostname)
      http_frontends += "  use_backend {0} if host_{1}\n".format(listener, cleanedUpHostname)

      backends += '''  option forwardfor
  http-request set-header X-Forwarded-Port %[dst_port]
  http-request add-header X-Forwarded-Proto https if { ssl_fc }
'''
    else:
      frontends += "  mode tcp\n"

    frontends += "  use_backend {0}\n".format(listener)

    for backend in sorted(app.backends, key = attrgetter('host', 'port')):
      backends += "  server {0}:{1}\n".format(backend.host, backend.port)


  config += head
  config += http_frontends
  config += frontends
  config += backends

  return config

print config(apps)
