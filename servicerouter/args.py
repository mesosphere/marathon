#!/usr/bin/env python2

import argparse
from bridge import Marathon, MarathonEventSubscriber
import json
from wsgiref.simple_server import make_server


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
