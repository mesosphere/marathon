---
title: Service Discovery & Load Balancing
---

# Service Discovery & Load Balancing

When your app is up and running, you need a way to send traffic to it, from other applications on the same cluster, and from external clients.

There are several ways to do this:

* [Mesos-DNS](https://github.com/mesosphere/mesos-dns) provides service discovery through the domain name system ([DNS](http://en.wikipedia.org/wiki/Domain_Name_System)).
* [Marathon-lb](https://github.com/mesosphere/marathon-lb) provides port-based service discovery using HAProxy, a lightweight TCP/HTTP proxy.
* [haproxy-marathon-bridge](https://github.com/mesosphere/marathon/blob/master/examples/haproxy-marathon-bridge) *(DEPRECATED)* is an example script that configures a local HAProxy installation.

For a detailed description of how ports work in Marathon, see [Networking](networking.html).

## Mesos-DNS

Mesos-DNS generates an SRV record for each Mesos task (including Marathon application instances) and translates these records to the IP address and port on the machine currently running each application.

Mesos-DNS is particularly useful when:

* apps are launched through multiple frameworks (not just Marathon).
* you are using an IP per container solution like [Project Calico](http://www.projectcalico.org/).
* you use random host port assignments in Marathon.

See the Mesos-DNS [documentation and tutorials page](http://mesosphere.github.io/mesos-dns/) for further information.

## Marathon-lb

An alternative way to implement service discovery is to run a TCP/HTTP proxy on each host in the cluster and transparently forward connections to the static service port on localhost to the dynamically assigned host/port combinations of the individual Marathon application instances (running Mesos *tasks*). Clients simply connect to the well-known defined service port and do not need to know the implementation details of discovery. This approach is sufficient if all apps are launched through Marathon.

Marathon-lb is a Dockerized application that includes both HAProxy an application that uses Marathon's REST API to regenerate the HAProxy configuration. It supports advanced functionality like SSL offloading, sticky connections, and VHost based load balancing, allowing you to specify virtual hosts for your Marathon applications.

When using marathon-lb, note that it is not necessary to set `requirePorts` to `true`, as described in the [networking documentation](networking.html).

For more information, see the [Marathon-lb repository](https://github.com/mesosphere/marathon-lb) or refer to the [marathon-lb documentation on the Mesosphere site](https://dcos.io/docs/latest/usage/service-discovery/marathon-lb/).

## haproxy-marathon-bridge (DEPRECATED)

Marathon ships with a simple shell script called `haproxy-marathon-bridge`, which uses Marathon's REST API to create a config file for HAProxy. The `haproxy-marathon-bridge` provides minimal functionality and is easier to understand for beginners or can serve as a starting point for a custom implementation. Note that this script is now deprecated and should not be used as-is in production. For production use, consider using Marathon-lb, above.

For a full list of HAProxy configurations, consult [the HAProxy configuration docs](http://cbonte.github.io/haproxy-dconv/configuration-1.5.html).
