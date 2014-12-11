#!/usr/bin/env python2

import bridge
import json
from wsgiref.simple_server import make_server

marathon = bridge.Marathon('http://localhost:8080')
subscriber = bridge.MarathonEventSubscriber(marathon, 'http://localhost:8000')


# TODO(cmaloney): Switch to a sane http server
# TODO(cmaloney): Good exception catching, etc
def application(env, start_response):
  subscriber.handle_event(json.load(env['wsgi.input']))
  #TODO(cmaloney): Make this have a simple useful webui for debugging / monitoring
  start_response('200 OK', [('Content-Type', 'text/html')])

  return []

httpd = make_server('', 8000, application)
print "Serving on port 8000..."

# Serve until process is killed
httpd.serve_forever()
