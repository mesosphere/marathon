#!/usr/bin/env python2

"""Overview:
  The servicerouter is a replacement for the haproxy-marathon-bridge.
  It reads the Marathon task information and dynamically generates
  haproxy configuration details.

  To gather the task information, the servicerouter needs to know where
  to find Marathon. The service configuration details are stored in Marathon
  environment variables.

  Every service port in Marathon can be configured independently.


Features:
  - Virtual Host aliases for services
  - Soft restart of haproxy
  - SSL Termination
  - (Optional): real-time update from Marathon events


Configuration:
  Service configuration lives in Marathon via environment variables.
  The servicerouter just needs to know where to find marathon.
  To run in listening mode you must also specify the address + port at
  which the servicerouter can be reached by marathon.


Usage:
  $ servicerouter.py --marathon http://marathon1:8080 \
        --haproxy-config /etc/haproxy/haproxy.cfg

  The user that executes servicerouter must have the permission to reload
  haproxy.


Environment Variables:
  HAPROXY_GROUP
    The group of servicerouter instances that point to the service.
    Service routers with the group '*' will collect all groups.

  HAPROXY_{n}_VHOST
    The Marathon HTTP Virtual Host proxy hostname to gather.
    Ex: HAPROXY_0_VHOST = 'marathon.mesosphere.com'

  HAPROXY_{n}_STICKY
    Enable sticky request routing for the service.
    Ex: HAPROXY_0_STICKY = true

  HAPROXY_{n}_REDIRECT_TO_HTTPS
    Redirect HTTP traffic to HTTPS.
    Ex: HAPROXY_0_REDIRECT_TO_HTTPS = true

  HAPROXY_{n}_SSL_CERT
    Enable the given SSL certificate for TLS/SSL traffic.
    Ex: HAPROXY_0_SSL_CERT = '/etc/ssl/certs/marathon.mesosphere.com'

  HAPROXY_{n}_BIND_ADDR
    Bind to the specific address for the service.
    Ex: HAPROXY_0_BIND_ADDR = '10.0.0.42'

  HAPROXY_{n}_MODE
    Set the connection mode to either TCP or HTTP. The default is TCP.
    Ex: HAPROXY_0_MODE = 'http'


Templates:
  The servicerouter searches for configuration files in the templates/
  directory. The templates/ directory contains servicerouter configuration
  settings and example usage. The templates/ directory is located in a relative
  path from where the script is run.

  HAPROXY_HEAD
    The head of the haproxy config. This contains global settings
    and defaults.

  HAPROXY_HTTP_FRONTEND_HEAD
    An HTTP frontend that binds to port *:80 by default and gathers
    all virtual hosts as defined by the HAPROXY_{n}_VHOST variable.

  HAPROXY_HTTPS_FRONTEND_HEAD
    An HTTPS frontend for encrypted connections that binds to port *:443 by
    default and gathers all virtual hosts as defined by the
    HAPROXY_{n}_VHOST variable. You must modify this file to
    include your certificate.

  HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS
    This template is used with backends where the
    HAPROXY_{n}_REDIRECT_TO_HTTPS environment variable is defined.

  HAPROXY_BACKEND_HTTP_OPTIONS
    Sets HTTP headers, for example X-Forwarded-For and X-Forwarded-Proto.

  HAPROXY_BACKEND_STICKY_OPTIONS
    Sets a cookie for services where HAPROXY_{n}_STICKY is true.

  HAPROXY_FRONTEND_HEAD
    Defines the address and port to bind to.

  HAPROXY_BACKEND_HEAD
    Defines the type of load balancing, roundrobin by default,
    and connection mode, TCP or HTTP.

  HAPROXY_HTTP_FRONTEND_ACL
    The ACL that glues a backend to the corresponding virtual host
    of the HAPROXY_HTTP_FRONTEND_HEAD.

  HAPROXY_HTTPS_FRONTEND_ACL
    The ACL that performs the SNI based hostname matching
    for the HAPROXY_HTTPS_FRONTEND_HEAD.

  HAPROXY_BACKEND_SERVER_OPTIONS
    The options for each physical server added to a backend.

  HAPROXY_FRONTEND_BACKEND_GLUE
    This option glues the backend to the frontend.


Operational Notes:
  - When a node in listening mode fails, remove the callback url for that
    node in marathon.
"""

from logging.handlers import SysLogHandler
from operator import attrgetter
from shutil import move
from tempfile import mkstemp
from textwrap import dedent
from wsgiref.simple_server import make_server
import argparse
import json
import logging
import os.path
import re
import requests
import subprocess
import sys


class ConfigTemplater(object):
    HAPROXY_HEAD = dedent('''\
    global
      daemon
      log 127.0.0.1 local0
      log 127.0.0.1 local1 notice
      maxconn 4096

    defaults
      log               global
      retries           3
      maxconn           2000
      timeout connect   5s
      timeout client    50s
      timeout server    50s

    listen stats
      bind 127.0.0.1:9090
      balance
      mode http
      stats enable
      stats auth admin:admin
    ''')

    HAPROXY_HTTP_FRONTEND_HEAD = dedent('''
    frontend marathon_http_in
      bind *:80
      mode http
    ''')

    # TODO(lloesche): make certificate path dynamic and allow multiple certs
    HAPROXY_HTTPS_FRONTEND_HEAD = dedent('''
    frontend marathon_https_in
      bind *:443 ssl crt /etc/ssl/mesosphere.com.pem
      mode http
    ''')

    HAPROXY_FRONTEND_HEAD = dedent('''
    frontend {backend}
      bind {bindAddr}:{servicePort}{sslCertOptions}
      mode {mode}
    ''')

    HAPROXY_BACKEND_HEAD = dedent('''
    backend {backend}
      balance roundrobin
      mode {mode}
    ''')

    HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS = '''\
  bind {bindAddr}:80
  redirect scheme https if !{{ ssl_fc }}
'''

    HAPROXY_HTTP_FRONTEND_ACL = '''\
  acl host_{cleanedUpHostname} hdr(host) -i {hostname}
  use_backend {backend} if host_{cleanedUpHostname}
'''

    HAPROXY_HTTPS_FRONTEND_ACL = '''\
  use_backend {backend} if {{ ssl_fc_sni {hostname} }}
'''

    HAPROXY_BACKEND_HTTP_OPTIONS = '''\
  option forwardfor
  http-request set-header X-Forwarded-Port %[dst_port]
  http-request add-header X-Forwarded-Proto https if { ssl_fc }
'''

    HAPROXY_BACKEND_STICKY_OPTIONS = '''\
  cookie mesosphere_server_id insert indirect nocache
'''

    HAPROXY_BACKEND_SERVER_OPTIONS = '''\
  server {serverName} {host}:{port}{cookieOptions}
'''

    HAPROXY_FRONTEND_BACKEND_GLUE = '''\
  use_backend {backend}
'''

    def __init__(self, directory='templates'):
        self.__template_directory = directory
        self.__load_templates()

    def __load_templates(self):
        '''Loads template files if they exist, othwerwise it sets defaults'''
        variables = [
            'HAPROXY_HEAD',
            'HAPROXY_HTTP_FRONTEND_HEAD',
            'HAPROXY_HTTPS_FRONTEND_HEAD',
            'HAPROXY_FRONTEND_HEAD',
            'HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS',
            'HAPROXY_BACKEND_HEAD',
            'HAPROXY_HTTP_FRONTEND_ACL',
            'HAPROXY_HTTPS_FRONTEND_ACL',
            'HAPROXY_BACKEND_HTTP_OPTIONS',
            'HAPROXY_BACKEND_STICKY_OPTIONS',
            'HAPROXY_BACKEND_SERVER_OPTIONS',
            'HAPROXY_FRONTEND_BACKEND_GLUE',
        ]

        for variable in variables:
            try:
                filename = os.path.join(self.__template_directory, variable)
                with open(filename) as f:
                    logger.info('overriding %s from %s', variable, filename)
                    setattr(self, variable, f.read())
            except IOError:
                logger.debug("setting default value for %s", variable)
                try:
                    setattr(self, variable, getattr(self.__class__, variable))
                except AttributeError:
                    logger.exception('default not found, aborting.')
                    raise

    @property
    def haproxy_head(self):
        return self.HAPROXY_HEAD

    @property
    def haproxy_http_frontend_head(self):
        return self.HAPROXY_HTTP_FRONTEND_HEAD

    @property
    def haproxy_https_frontend_head(self):
        return self.HAPROXY_HTTPS_FRONTEND_HEAD

    @property
    def haproxy_frontend_head(self):
        return self.HAPROXY_FRONTEND_HEAD

    @property
    def haproxy_backend_redirect_http_to_https(self):
        return self.HAPROXY_BACKEND_REDIRECT_HTTP_TO_HTTPS

    @property
    def haproxy_backend_head(self):
        return self.HAPROXY_BACKEND_HEAD

    @property
    def haproxy_http_frontend_acl(self):
        return self.HAPROXY_HTTP_FRONTEND_ACL

    @property
    def haproxy_https_frontend_acl(self):
        return self.HAPROXY_HTTPS_FRONTEND_ACL

    @property
    def haproxy_backend_http_options(self):
        return self.HAPROXY_BACKEND_HTTP_OPTIONS

    @property
    def haproxy_backend_sticky_options(self):
        return self.HAPROXY_BACKEND_STICKY_OPTIONS

    @property
    def haproxy_backend_server_options(self):
        return self.HAPROXY_BACKEND_SERVER_OPTIONS

    @property
    def haproxy_frontend_backend_glue(self):
        return self.HAPROXY_FRONTEND_BACKEND_GLUE


def string_to_bool(s):
    return s.lower() in ["true", "t", "yes", "y"]


def set_hostname(x, y):
    x.hostname = y


def set_sticky(x, y):
    x.sticky = string_to_bool(y)


def set_redirect_http_to_https(x, y):
    x.redirectHttpToHttps = string_to_bool(y)


def set_sslCert(x, y):
    x.sslCert = y


def set_bindAddr(x, y):
    x.bindAddr = y


def set_mode(x, y):
    x.mode = y

env_keys = {
    'HAPROXY_{0}_VHOST': set_hostname,
    'HAPROXY_{0}_STICKY': set_sticky,
    'HAPROXY_{0}_REDIRECT_TO_HTTPS': set_redirect_http_to_https,
    'HAPROXY_{0}_SSL_CERT': set_sslCert,
    'HAPROXY_{0}_BIND_ADDR': set_bindAddr,
    'HAPROXY_{0}_MODE': set_mode
}

logger = logging.getLogger('servicerouter')


class MarathonBackend(object):

    def __init__(self, host, port):
        self.host = host
        self.port = port

    def __hash__(self):
        return hash((self.host, self.port))

    def __repr__(self):
        return "MarathonBackend(%r, %r)" % (self.host, self.port)


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
        self.mode = 'tcp'
        self.groups = frozenset()

    def add_backend(self, host, port):
        self.backends.add(MarathonBackend(host, port))

    def __hash__(self):
        return hash(self.servicePort)

    def __eq__(self, other):
        return self.servicePort == other.servicePort

    def __repr__(self):
        return "MarathonService(%r, %r)" % (self.appId, self.servicePort)


class MarathonApp(object):

    def __init__(self, marathon, appId):
        self.app = marathon.get_app(appId)
        self.groups = frozenset()
        self.appId = appId

        # port -> MarathonService
        self.services = dict()

    def __hash__(self):
        return hash(self.appId)

    def __eq__(self, other):
        return self.appId == other.appId


class Marathon(object):

    def __init__(self, hosts):
        # TODO(cmaloney): Support getting master list from zookeeper
        self.__hosts = hosts

    def api_req_raw(self, method, path, body=None, **kwargs):
        for host in self.__hosts:
            path_str = os.path.join(host, 'v2')
            if len(path) == 2:
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
        return self.api_req(
                'POST',
                ['eventSubscriptions'],
                params={'callbackUrl': callbackUrl})

    def remove_subscriber(self, callbackUrl):
        return self.api_req(
                'DELETE',
                ['eventSubscriptions'],
                params={'callbackUrl': callbackUrl})


def has_group(groups, app_groups):
    # All groups / wildcard match
    if '*' in groups:
        return True

    # empty group only
    if len(groups) == 0 and len(app_groups) == 0:
        return True

    # Contains matching groups
    if (len(frozenset(app_groups) & groups)):
        return True

    return False


def config(apps, groups):
    logger.info("generating config")
    templater = ConfigTemplater()
    config = templater.haproxy_head
    groups = frozenset(groups)

    http_frontends = templater.haproxy_http_frontend_head
    https_frontends = templater.haproxy_https_frontend_head
    frontends = str()
    backends = str()

    for app in sorted(apps, key=attrgetter('appId', 'servicePort')):
        # App only applies if we have it's group
        if not has_group(groups, app.groups):
            continue

        logger.debug("configuring app %s", app.appId)
        backend = app.appId[1:].replace('/', '_') + '_' + str(app.servicePort)

        logger.debug("frontend at %s:%d with backend %s",
                     app.bindAddr, app.servicePort, backend)

        frontend_head = templater.haproxy_frontend_head
        frontends += frontend_head.format(
            bindAddr=app.bindAddr,
            backend=backend,
            servicePort=app.servicePort,
            mode=app.mode,
            sslCertOptions=' ssl crt ' + app.sslCert if app.sslCert else ''
        )

        if app.redirectHttpToHttps:
            logger.debug("rule to redirect http to https traffic")
            haproxy_backend_redirect_http_to_https = \
                templater.haproxy_backend_redirect_http_to_https
            frontends += haproxy_backend_redirect_http_to_https.format(
                bindAddr=app.bindAddr)

        backend_head = templater.haproxy_backend_head
        backends += backend_head.format(
            backend=backend,
            mode=app.mode
        )

        # if a hostname is set we add the app to the vhost section
        # of our haproxy config
        # TODO(lloesche): Check if the hostname is already defined by another
        # service
        if app.hostname:
            logger.debug(
                "adding virtual host for app with hostname %s", app.hostname)
            cleanedUpHostname = re.sub(r'[^a-zA-Z0-9\-]', '_', app.hostname)

            http_frontend_acl = templater.haproxy_http_frontend_acl
            http_frontends += http_frontend_acl.format(
                cleanedUpHostname=cleanedUpHostname,
                hostname=app.hostname,
                backend=backend
            )
            https_frontend_acl = templater.haproxy_https_frontend_acl
            https_frontends += https_frontend_acl.format(
               hostname=app.hostname,
               backend=backend
            )
            backends += templater.haproxy_backend_http_options

        if app.sticky:
            logger.debug("turning on sticky sessions")
            backends += templater.haproxy_backend_sticky_options

        frontend_backend_glue = templater.haproxy_frontend_backend_glue
        frontends += frontend_backend_glue.format(backend=backend)

        key_func = attrgetter('host', 'port')
        for backendServer in sorted(app.backends, key=key_func):
            logger.debug(
                "backend server at %s:%d",
                backendServer.host,
                backendServer.port)
            serverName = re.sub(
                r'[^a-zA-Z0-9\-]', '_',
                backendServer.host + '_' + str(backendServer.port))

            backend_server_options = templater.haproxy_backend_server_options
            backends += backend_server_options.format(
                host=backendServer.host,
                port=backendServer.port,
                serverName=serverName,
                cookieOptions=' check cookie ' +
                serverName if app.sticky else ''
            )

    config += http_frontends
    config += https_frontends
    config += frontends
    config += backends

    return config


def reloadConfig():
    logger.debug("trying to find out how to reload the configuration")
    if os.path.isfile('/etc/init/haproxy.conf'):
        logger.debug("we seem to be running on an Upstart based system")
        reloadCommand = ['reload', 'haproxy']
    elif (os.path.isfile('/usr/lib/systemd/system/haproxy.service') or
            os.path.isfile('/etc/systemd/system/haproxy.service')):
        logger.debug("we seem to be running on systemd based system")
        reloadCommand = ['systemctl', 'reload', 'haproxy']
    else:
        logger.debug("we seem to be running on a sysvinit based system")
        reloadCommand = ['/etc/init.d/haproxy', 'reload']

    logger.info("reloading using %s", " ".join(reloadCommand))
    try:
        subprocess.check_call(reloadCommand)
    except OSError as ex:
        logger.error("unable to reload config using command %s",
                     " ".join(reloadCommand))
        logger.error("OSError: %s", ex)
    except subprocess.CalledProcessError as ex:
        logger.error("unable to reload config using command %s",
                     " ".join(reloadCommand))
        logger.error("reload returned non-zero: %s", ex)


def writeConfig(config, config_file):
    # Write config to a temporary location
    fd, haproxyTempConfigFile = mkstemp()
    logger.debug("writing config to temp file %s", haproxyTempConfigFile)
    with os.fdopen(fd, 'w') as haproxyTempConfig:
        haproxyTempConfig.write(config)

    # Move into place
    logger.debug("moving temp file %s to %s",
                 haproxyTempConfigFile,
                 config_file)
    move(haproxyTempConfigFile, config_file)


def compareWriteAndReloadConfig(config, config_file):
    # See if the last config on disk matches this, and if so don't reload
    # haproxy
    runningConfig = str()
    try:
        logger.debug("reading running config from %s", config_file)
        with open(config_file, "r") as f:
            runningConfig = f.read()
    except IOError:
        logger.warning("couldn't open config file for reading")

    if runningConfig != config:
        logger.info(
            "running config is different from generated config - reloading")
        writeConfig(config, config_file)
        reloadConfig()


def get_apps(marathon):
    tasks = marathon.tasks()

    apps = dict()

    for task in tasks:
        # For each task, extract the app it belongs to and add a
        # backend for each service it provides
        if 'servicePorts' not in task:
            continue

        for i in xrange(len(task['servicePorts'])):
            # Marathon 0.7.6 bug workaround
            if len(task['host']) == 0:
                logger.warning("Ignoring Marathon task without host " +
                               task['id'])
                continue

            servicePort = task['servicePorts'][i]
            port = task['ports'][i] if len(task['ports']) else servicePort
            appId = task['appId']

            if appId not in apps:
                app_tmp = MarathonApp(marathon, appId)
                if 'HAPROXY_GROUP' in app_tmp.app['env']:
                    app_tmp.groups = \
                            app_tmp.app['env']['HAPROXY_GROUP'].split(',')
                apps[appId] = app_tmp

            app = apps[appId]
            if servicePort not in app.services:
                app.services[servicePort] = MarathonService(
                    appId, servicePort)

            service = app.services[servicePort]
            service.groups = app.groups

            # Load environment variable configuration
            # TODO(cmaloney): Move to labels once those are supported
            # throughout the stack
            for key_unformatted in env_keys:
                key = key_unformatted.format(i)
                if key in app.app[u'env']:
                    func = env_keys[key_unformatted]
                    func(service, app.app[u'env'][key])

            service.add_backend(task['host'], port)

    # Convert into a list for easier consumption
    apps_list = list()
    for app in apps.values():
        for service in app.services.values():
            apps_list.append(service)
    return apps_list


def regenerate_config(apps, config_file, groups):
    compareWriteAndReloadConfig(config(apps, groups), config_file)


class MarathonEventSubscriber(object):

    def __init__(self, marathon, addr, config_file, groups):
        marathon.add_subscriber(addr)
        self.__marathon = marathon
        # appId -> MarathonApp
        self.__apps = dict()
        self.__config_file = config_file
        self.__groups = groups

        # Fetch the base data
        self.reset_from_tasks()

    def reset_from_tasks(self):
        self.__apps = get_apps(self.__marathon)
        regenerate_config(self.__apps, self.__config_file, self.__groups)

    def handle_event(self, event):
        if event['eventType'] == 'status_update_event':
            # TODO (cmaloney): Handle events more intelligently so we don't
            # unnecessarily hammer the Marathon API.
            self.reset_from_tasks()


def get_arg_parser():
    parser = argparse.ArgumentParser(
        description="Marathon HAProxy Service Router")
    parser.add_argument("--longhelp",
                        help="Print out configuration details",
                        action="store_true"
                        )
    parser.add_argument("--marathon", "-m",
                        nargs="+",
                        help="Marathon endpoint, eg. -m " +
                             "http://marathon1:8080 -m http://marathon2:8080"
                        )
    parser.add_argument("--listening", "-l",
                        help="The HTTP address that Marathon can call this " +
                             "script back at (http://lb1:8080)"
                        )

    
    default_log_socket = "/dev/log"
    if sys.platform == "darwin":
        default_log_socket = "/var/run/syslog"

    parser.add_argument("--syslog-socket",
                        help="Socket to write syslog messages to",
                        default=default_log_socket
                        )
    parser.add_argument("--haproxy-config",
                        help="Location of haproxy configuration",
                        default="/etc/haproxy/haproxy.cfg"
                        )
    parser.add_argument("--group",
                        help="Only generate config for apps which list the "
                        "specified names. Defaults to apps without groups. "
                        "Use '*' to match all groups",
                        action="append",
                        default=list())

    return parser


def run_server(marathon, callback_url, config_file, groups):
    subscriber = MarathonEventSubscriber(marathon,
                                         callback_url,
                                         config_file,
                                         groups)

    # TODO(cmaloney): Switch to a sane http server
    # TODO(cmaloney): Good exception catching, etc
    def wsgi_app(env, start_response):
        length = int(env['CONTENT_LENGTH'])
        data = env['wsgi.input'].read(length)
        subscriber.handle_event(json.loads(data))
        # TODO(cmaloney): Make this have a simple useful webui for debugging /
        # monitoring
        start_response('200 OK', [('Content-Type', 'text/html')])

        return "Got it\n"

    try:
        port = int(callback_url.split(':')[-1])
    except ValueError:
        port = 8000  # no port or invalid port specified
    logger.info("Serving on port {}...".format(port))
    httpd = make_server('', port, wsgi_app)
    httpd.serve_forever()


def setup_logging(syslog_socket):
    logger.setLevel(logging.DEBUG)

    syslogHandler = SysLogHandler(args.syslog_socket)
    consoleHandler = logging.StreamHandler()
    formatter = logging.Formatter('%(name)s: %(message)s')
    syslogHandler.setFormatter(formatter)
    consoleHandler.setFormatter(formatter)
    # syslogHandler.setLevel(logging.ERROR)
    logger.addHandler(syslogHandler)
    logger.addHandler(consoleHandler)


if __name__ == '__main__':
    # Process arguments
    arg_parser = get_arg_parser()
    args = arg_parser.parse_args()

    # Print the long help text if flag is set
    if args.longhelp:
        print __doc__
        sys.exit()
    # otherwise make sure that a Marathon URL was specified
    else:
        if args.marathon is None:
            arg_parser.error('argument --marathon/-m is required')

    # Setup logging
    setup_logging(args.syslog_socket)

    # Marathon API connector
    marathon = Marathon(args.marathon)

    # If in listening mode, spawn a webserver waiting for events. Otherwise
    # just write the config.
    if args.listening:
        run_server(marathon, args.listening, args.haproxy_config, args.group)
    else:
        # Generate base config
        regenerate_config(get_apps(marathon), args.haproxy_config, args.group)
