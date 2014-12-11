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

import os.path
import requests

# TODO (cmaloney): Merge with lukas' definitions
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
    MarathonApp('/mm/service/collector', 'collector.mesosphere.com', 9990, [
      MarathonBackend('srv4.hw.ca1.mesosphere.com', 31006)
    ])
  ]


#TODO(cmaloney): This is a generic marathon api wrapper
class Marathon(object):
  # TODO(cmaloney): Pass in configuration.
  def __init__(self, hosts):
    self.__hosts = hosts

  def api_req_raw(self, method, path, body=None, **kwargs):
    for host in self.__hosts:
      response = requests.request(
          method,
          os.path.join(host, 'v2', *path),
          headers={
              'Accept': 'application/json',
              'Content-Type': 'application/json'
              },
          **kwargs
          )
      if reposnse.status_code == 200:
        break

    response.raise_for_status()
    return response

  def api_req(self, method, path, **kwargs):
    return self.api_req_raw(method, path, **kwargs).json()

  def create(self, app_json):
    return self.api_req('POST', ['apps'], app_json)

  def get_app(self, appid):
    return self.api_req('GET', ['apps', appid])

  # Lists all running apps.
  def list(self):
    return self.api_req('GET', ['apps'])["apps"]

  def tasks(self):
    return self.api_req('GET', ['tasks'])["tasks"]

  def add_subscriber(self, callbackUrl):
    return self.api_req('POST', ['eventSubscriptions'], params={'callbackUrl': callbackUrl})

  def remove_subscriber(self, callbackUrl):
    return self.api_req('DELETE', ['eventSubscriptions'], params={'callbackUrl': callbackUrl})


"""
{
  "eventType": "status_update_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "slaveId": "20140909-054127-177048842-5050-1494-0",
  "taskId": "my-app_0-1396592784349",
  "taskStatus": "TASK_RUNNING",
  "appId": "/my-app",
  "host": "slave-1234.acme.org",
  "ports": [31372],
  "version": "2014-04-04T06:26:23.051Z"
}
"""


class MarathonEventSubscriber(object):
  def __init__(self, marathon, addr):
    self.__marathon = marathon
    self.__apps = set()
    marathon.add_subscriber(addr)

    # Fetch the base data
    self.update_from_tasks()

  def update_from_tasks(self):
    tasks = marathon.tasks()
    # For each task, extract self.__apps
    # and the backends within each app.
    raise NotImplementedError()


  def handle_event(self, json):
    # On "eventType": "status_update_event"
    # Update self.__apps
    raise NotImplementedError()


#TODO(cmaloney): servicePorts
class Task(object):
  def __init__(self, host, ports):
    self.host = host
    self.ports = ports


class App(object):
  def __init__(self, appId, servicePorts, tasks):
    self.appId = appId
    self.servicePorts = servicePorts
    self.tasks = tasks
