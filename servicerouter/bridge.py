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

  def __hash__(self):
    return hash((self.host, self.port))

class MarathonService(object):
  def __init__(self, appId, servicePort):
    self.appId = appId
    self.servicePort = servicePort
    self.backends = set()
    self.hostname = None

  def add_backend(self, host, port):
    self.backends.insert(MarathonBackend(host, port))

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
      response = requests.request(
          method,
          os.path.join(host, 'v2', *path),
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


class MarathonEventSubscriber(object):
  def __init__(self, marathon, addr):
    self.__marathon = marathon
    #NOTE: Convert to a list before handing to haproxycfggenerator
    #appId -> MarathonApp
    self.__apps = dict()
    marathon.add_subscriber(addr)

    # Fetch the base data
    self.update_from_tasks()

  def reset_from_tasks(self):
    tasks = marathon.tasks()

    self.__apps = dict()

    for task in tasks:
      # For each task, extract self.__apps
      # and the backends within each app.
      for i in xrange(len(task.servicePorts)):
        servicePort = task.servicePorts[i]
        port = task.ports[i]

        if task.appId not in self.__apps:
          self.__apps[task.appId] = MarathonApp(task.appId)

        app = self.__apps[appId]
        if task.servicePort not in app.services:
          app.services[task.servicePort] = MarathonService(task.appId, servicePort)

        service = app.services[task.servicePort]

        service.add_backend(task.host, port)

  def handle_event(self, event):
    if event.eventType == 'status_update_event':
      #TODO (cmaloney): Handle events more intelligently so that we add/remove things well
      self.reset_from_tasks()

