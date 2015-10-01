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
A sample script that does this is provided with Marathon, the `haproxy-marathon-bridge`.

Note that service ports should not be confused with the host ports of the individual tasks. 
Since multiple tasks may run on the same node, host ports are usually assigned dynamically per task
unless you use `"requirePorts"` or set the `"hostPort"` to non-zero in the docker `"portMappings"`.
From within the task execution the dynamically assigned host ports are exposed via environment variables. 
See [task environment variables - host ports]({{ site.baseurl }}/docs/task-environment-vars.html#host-ports) for more information.

Port information for each running task can be queried via the
[tasks API endpoint]({{ site.baseurl }}/docs/rest-api.html#get-/v2/tasks)
at `<marathon host>:<port>/v2/tasks`

## Using HAProxy
Marathon ships with a simple shell script called `haproxy-marathon-bridge` as well as a more advanced Python script 'servicerouter.py'.
Both can turn the Marathon's REST API's list of running tasks into a config file for HAProxy, a lightweight TCP/HTTP proxy.
The `haproxy-marathon-bridge` provides a minimum set of functionality and is easier to understand for beginners. `servicerouter.py`
on the other hand supports more advanced functionality like SSL offloading, sticky connections and VHost based load balancing.

### haproxy-marathon-bridge
To generate an HAProxy configuration from Marathon running at `localhost:8080` with the `haproxy-marathon-bridge` script:

``` console
$ ./bin/haproxy-marathon-bridge localhost:8080 > /etc/haproxy/haproxy.cfg
```

To reload the HAProxy configuration without interrupting existing connections:

``` console
$ haproxy -f haproxy.cfg -p haproxy.pid -sf $(cat haproxy.pid)
```

The configuration script and reload could be triggered frequently by Cron, to
keep track of topology changes. If a node goes away between reloads, HAProxy's
health check will catch it and stop sending traffic to that node.

To facilitate this setup, the `haproxy-marathon-bridge` script can be invoked in
an alternate way which installs the script itself, HAProxy and a cronjob that
once a minute pings one of the Marathon servers specified and refreshes
HAProxy if anything has changed.

``` console
$ ./bin/haproxy-marathon-bridge install_haproxy_system localhost:8080
```

- The list of Marathons to ping is stored one per line in
  `/etc/haproxy-marathon-bridge/marathons`
- The script is installed as `/usr/local/bin/haproxy-marathon-bridge`
- The cronjob is installed as `/etc/cron.d/haproxy-marathon-bridge`
  and run as root.

The provided script is just a basic example. For a full list of options, check the
[HAProxy configuration docs](http://cbonte.github.io/haproxy-dconv/configuration-1.5.html).

### servicerouter.py
To generate an HAProxy configuration from Marathon running at `localhost:8080` with the `servicerouter.py` script:

``` console
$ ./bin/servicerouter.py --marathon http://localhost:8080 --haproxy-config /etc/haproxy/haproxy.cfg
```

This will refresh haproxy.cfg, and if there were any changes, then it will automatically reload HAproxy.

servicerouter.py has a lot of additional functionality like sticky sessions, HTTP to HTTPS redirection, SSL offloading,
VHost support and templating capabilities.

To get the full servicerouter.py documentation run:
``` console
$ ./bin/servicerouter.py --help
```
