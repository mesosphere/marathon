---
title: Service Discovery & Load Balancing
---

# Service Discovery & Load Balancing

Once our app is up and running, we need a way to send outside traffic to it. If we're running multiple apps, they also need a way to find each other. We can use [Mesos-DNS](https://github.com/mesosphere/mesos-dns) for service discovery through the domain name system ([DNS](http://en.wikipedia.org/wiki/Domain_Name_System)). Mesos-DNS generates a hostname for each application running on Mesos and translates these names to the IP address and port on the machine currently running each application. Mesos-DNS is particularly useful if the apps are launched through multiple frameworks (not just Marathon). Check the Mesos-DNS [documentation and tutorials page](http://mesosphere.github.io/mesos-dns/) for further information. 

An alternative way to implement service discovery is to run a TCP proxy on each host in the cluster, and transparently forward 
connections to the static service port on localhost to the dynamically assigned host/port combinations of the individual 
app instances (= *tasks*). That way, clients simply connect to that port, and the implementation details of discovery 
are completely abstracted away. This approach is sufficient if all apps are launched through Marathon. 

## Service Ports Assignment

When you create a new application in Marathon (either through the REST API or the front end),
you may assign one or more service ports to it. 
You can specify all valid port numbers as service ports or you can use 0 to indicate that Marathon should allocate
free service ports to the app automatically. If you do choose your own service port, you have to ensure yourself
that it is unique across all of your applications.

For applications using Docker and `BRIDGE` networking, you assign the servicePorts in the 
`portMappings`. For applications with `HOST` networking or without docker, you use the `ports` attribute
to assign the service ports. Look at the [REST API docs]({{ site.baseurl }}/docs/rest-api.html#ports-array-of-integers)
for details.

Marathon will ensure that all dynamically assigned service ports are unique for this Marathon framework instance.
If you run multiple Marathon frameworks on the cluster, you should make sure that their ranges for the service
ports do not overlap. You can configure this via the `--local_port_min` and `--local_port_max` 
[command line flags](({{ site.baseurl }}/docs/command-line-flags.html)).
Marathon does not make use of these assigned service ports itself. 
It is the responsibility of a separate component to make use of this information and set up load balancing accordingly. 
A script that does this is provided with Marathon, `marathon-lb`.

Note that service ports should not be confused with the host ports of the individual tasks. 
Since multiple tasks may run on the same node, host ports are usually assigned dynamically per task
unless you use `"requirePorts"` or set the `"hostPort"` to non-zero in the docker `"portMappings"`.
From within the task execution the dynamically assigned host ports are exposed via environment variables. 
See [task environment variables - host ports]({{ site.baseurl }}/docs/task-environment-vars.html#host-ports) for more information.

Port information for each running task can be queried via the
[tasks API endpoint]({{ site.baseurl }}/docs/rest-api.html#get-/v2/tasks)
at `<marathon host>:<port>/v2/tasks`

## Using HAProxy
A script called `marathon-lb` turns Marathon's REST API's list of running tasks into a config file for HAProxy, a lightweight TCP/HTTP proxy. `marathon-lb` supports advanced functionality like SSL offloading, sticky connections, and VHost based load balancing. [More information about `marathon-lb`](https://docs.mesosphere.com/administration/service-discovery-with-marathon-lb/service-discovery-and-load-balancing-with-marathon-lb/).

Alternatively, a simpler script called [haproxy-marathon bridge](https://raw.githubusercontent.com/mesosphere/marathon/master/examples/haproxy-marathon-bridge) can be used, but it has been deprecated.

For a full list of HAProxy configurations, consult [the HAProxy configuration docs](http://cbonte.github.io/haproxy-dconv/configuration-1.5.html).
