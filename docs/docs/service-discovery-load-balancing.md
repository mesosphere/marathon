---
title: Service Discovery & Load Balancing
---

# Service Discovery & Load Balancing

Once our app is up and running, we need a way to send outside traffic to it. If we're running multiple apps, they also need a way to find each other. A simple way to do this is to run a TCP proxy on each host in the cluster, and transparently forward a static port on localhost to the hosts that are running the app. That way, clients simply connect to that port, and the implementation details of discovery are completely abstracted away.

## Ports Assignment

When an application is created in Marathon (either through the REST API or the front end) - you may assign it one or more ports. These ports may be any valid port number or 0, which Marathon will use to randomly assign a port number.

This port is used to ensure no two applications can be run using Marathon with overlapping port assignments (i.e. that the ports are globally unique).

However, this is not the port that each instance of that application is assigned when running on slave nodes within the Mesos cluster. Since multiple instances may run on the same node, each instance (or *task*) is assigned a random port. This can be accessed from the `$PORT` environment variable which is set by Marathon.

Port information for each running task can be queried via the
[tasks API endpoint]({{ site.baseurl }}/docs/rest-api.html#get-/v2/tasks)
at `<marathon host>:<port>/v2/tasks`

## Using HAProxy
Marathon ships with a simple shell script called `haproxy_marathon_bridge` that turns the Marathon's REST API's list of running tasks into a config file for HAProxy, a lightweight TCP/HTTP proxy.
To generate an HAProxy configuration from Marathon running at `localhost:8080`, use the `haproxy_marathon_bridge` script:

``` console
$ bin/haproxy_marathon_bridge localhost:8080 > haproxy.cfg
```

To reload the HAProxy configuration without interrupting existing connections:

``` console
$ haproxy -f haproxy.cfg -p haproxy.pid -sf $(cat haproxy.pid)
```

The configuration script and reload could be triggered frequently by Cron, to
keep track of topology changes. If a node goes away between reloads, HAProxy's
health check will catch it and stop sending traffic to that node.

To facilitate this setup, the `haproxy_marathon_bridge` script can be invoked in
an alternate way which installs the script itself, HAProxy and a cronjob that
once a minute pings one of the Marathon servers specified and refreshes
HAProxy if anything has changed.

``` console
$ install_haproxy_system localhost:8080
```

- The list of Marathons to ping is stored one per line in
  `/etc/haproxy_marathon_bridge/marathons`
- The script is installed as `/usr/local/bin/haproxy_marathon_bridge`
- The cronjob is installed as `/etc/cron.d/haproxy_marathon_bridge`
  and run as root.

The provided script is just a basic example. For a full list of options, check the
[HAProxy configuration docs](http://cbonte.github.io/haproxy-dconv/configuration-1.5.html).
