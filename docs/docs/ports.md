---
title: Ports
---

# Ports

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span>
    Several fields of the ports API have been deprecated with Marathon v1.5 in favor of the updated [Networking API](networking.html).
    Use this deprecated API at your own risk. Deprecated fields may be removed in a future Marathon release.
    We appreciate <a href="https://jira.mesosphere.com/browse/MARATHON-7026">your feedback</a>!
</div>


Port configuration for applications in Marathon can be confusing and there is [an outstanding issue](https://github.com/mesosphere/marathon/issues/670) to redesign the ports API. This page attempts to explain more clearly how they work.

If you are running Marathon within a [DC/OS cluster](https://dcos.io/get-started) you can use [virtual addresses (VIPs)](https://dcos.io/docs/1.9/usage/service-discovery/load-balancing-vips/virtual-ip-addresses/) to make ports management easier. VIPs simplify inter-app communication and implement a reliable service-oriented architecture. VIPs map traffic from a single virtual address to multiple IP addresses and ports.

## Definitions

*containerPort*: A _container port_ specifies a port within a container. This is only necessary as part of a _port mapping_ when using `BRIDGE` or `USER` mode networking with a Docker container.

*hostPort*: A _host port_ specifies a port on the host to bind to. When used with `BRIDGE` or `USER` mode networking, you specify a _port mapping_ from a _host port_ to a _container port_. In `HOST` networking, requested ports are _host ports_ by default. Note that only _host ports_ are made available to a task through environment variables.

*BRIDGE networking*: used by Docker applications that specify `BRIDGE` mode networking. In this mode, container ports (a port within the container) are mapped to host ports (a port on the host machine). In this mode, applications bind to the specified ports within the container and Docker networking binds to the specified ports on the host.

*USER networking*: used by Docker applications that specify `USER` mode networking. In this mode, container ports (a port within the container) are mapped to host ports (a port on the host machine). In this mode, applications bind to the specified ports within the container and Docker networking binds to the specified ports on the host. `USER` network mode is expected to be useful when integrating with "user-defined" Docker networks. In the Mesos world such networks are often made accessible via CNI plugins used in concert with a Mesos CNI network isolator.

*HOST networking*: used by non-Docker Marathon applications and Docker applications that use `HOST` mode networking. In this mode, applications bind directly to one or more ports on the host machine.

*portMapping*: In Docker `BRIDGE` mode, a port mapping is necessary for every port that should be reachable from outside of your container. A port mapping is a tuple containing a host port, container port, service port and protocol. Multiple _port mappings_ may be specified for a Marathon application; an unspecified `hostPort` defaults to `0` (meaning that Marathon will assign one at random). In Docker `USER` mode the semantic for `hostPort` slightly changes: `hostPort` is not required for `USER` mode and if left unspecified Marathon WILL NOT automatically allocate one at random. This allows containers to be deployed on `USER` networks that include `containerPort` and discovery information, but do NOT expose those ports on the host network (and by implication would not consume host port resources).

*ports*: The _ports_ array is used to define ports that should be considered as part of a resource offer in `HOST` mode. It is necessary only if no port mappings are specified. Only one of _ports_ and _portDefinitions_ should be defined for an application.

*portDefinitions*: The _portDefinitions_ array is used to define ports that should be considered as part of a resource offer. It is necessary only to define this array if you are using `HOST` networking and no port mappings are specified. This array is meant to replace the _ports_ array, and makes it possible to specify a port name, protocol and labels. Only one of _ports_ and _portDefinitions_ should be defined for an application.

**Note:** Pods use the `endpoints` array instead of the `portDefinitions` array to define and name ports. See the [pods documentation]({{ site.baseurl }}/docs/pods.html) for more information.

*protocol*: _Protocol_ specifies the internet protocol to use for a port (e.g. `tcp`, `udp` or `udp,tcp` for both). This is only necessary as part of a _port mapping_ when using `BRIDGE` or `USER` mode networking with a Docker container.

*requirePorts*: _requirePorts_ is a property that specifies whether Marathon should specifically look for specified ports in the resource offers it receives. This ensures that these ports are free and available to be bound to on the Mesos agent. This does not apply to `BRIDGE` or `USER` mode networking.

*servicePort*: When you create a new application in Marathon (either through the REST API or the front end), you may assign one or more service ports to it. You can specify all valid port numbers as service ports or you can use 0 to indicate that Marathon should allocate free service ports to the app automatically. If you do choose your own service port, you have to ensure yourself that it is unique across all of your applications.

## Use Cases for Different ports

The 3 ports defined (containerPort, hostPort, servicePort) along with the label for a VIP_0 in a port mapping can be confusing.  The following is another way of considering these ports using an ACME app as an example.  Let's say the actual application opens port :8080.   All applications on Mesos are hosted in containers. Containers can be run in bridge or host mode.  When in host mode, the container will have the host network interface defined in the container namespace.  The container will not do any port magic and the application will open up port :8080 on the host network interface.   In this case there is no container port, and the `hostPort` is :8080.
The container could be run in bridge mode.  In this case, the container gets its own network interface and the `containerPort` is :8080.   In bridge mode, the container will open a pseudo random port on the host and bridge/NAT communication to the container port.   For example, lets say the port is :31000.   Now the `containerPort` is :8080, but the `hostPort` is :31000.  Clients to this service will open port :31000 on the host the service is running on and the container will route that to the internal port :8080 in the container.
Now let's say you have 3 instances of App ACME, for example 10.0.0.2:31000, 10.0.0.2:31001 and 10.0.0.3:31000.  There is a common need to have a port that will route with some algorithm to each of those instances.  This "port" is metadata which is specific to this application.  All instances of the application will be hosted behind this port.  In Marathon this is referred to as the `servicePort`.  As part of the ACME app definition you can specify a `servicePort` of :8080 for instance.  It is important to recognize that this is metadata and Marathon doesn't do anything with this information... which can be confusing.   It is however tracked and provided by Marathon.   The purpose is to provide metadata about the service, in this case the ACME app.    It is expected that a user will configure a load balancer to host a port :8080 (or the `servicePort`) and route to each of the instances of this application.   All the information is queryable from Marathon.  The marathon-lb service when configured, does exactly this.  marathon-lb will register all instances of an app and route to its configured `servicePort`.  In this configuration, a client will connect to a load balancer at port :8080 (`servicePort`) which will route (with some algorithm) to 10.0.0.2:31000 (`hostPort`) which will route to :8080 (`containerPort`) of the internal application.

`VIP_0` defined in `portDefinitions` as a label is like `servicePort` in that it is informational and implementation dependent.   However, when used with DC/OS, services internal to DC/OS will make available to the cluster that DNS name and port as a routing to services.   An example configuration:

```app_def['portDefinitions'] = [{
        "port": 0,
        "protocol": "tcp",
        "name": "acme",
        "labels": {
            "VIP_0": "/acme:10000"
        }
    }]
```
This configuration will create a FQN of `acme.marathon.l4lb.thisdcos.directory` at port :10000 which will load balance all instances of the application.  It is important to note that Marathon itself doesn't do anything with this configuration it is metadata that Marathon manages for the application.  A client in DC/OS could open a connection to `acme.marathon.l4lb.thisdcos.directory` at port :10000, which would route to 10.0.0.2:31000 which will route to the containerPort of :8080.


## Random Port Assignment

Using the value 0 for any port settings indicates to Marathon that you would like a random port assignment. However, if `containerPort` is set to 0 within a `portMapping`, it is set to the same value as `hostPort`.

## Environment Variables

Each *host port* value is exposed to the running application instance via environment variables `$PORT0`, `$PORT1`, etc. Each Marathon application is given a single port by default, so `$PORT0` is always available. These variables are available inside a Docker container being run by Marathon too. Additionally, if the port is named `NAME`, it will also be accessible via the environment variable, `$PORT_NAME`.

**Note:** For pods, this environment variable is accessible via `ENDPOINT_NAME`.

When using `BRIDGE` or `USER` mode networking, be sure to bind your application to the `containerPort`s you have specified in your `portMapping`s. However, if you have set `containerPort` to 0 then this will be the same as `hostPort` and you can use the `$PORT` environment variables.

## Example Configuration

### Host Mode

Host mode networking is the default networking mode for Docker containers and the only networking mode for non-Docker applications. Note that it not necessary to `EXPOSE` ports in your Dockerfile.

#### Enabling Host Mode

Host mode is enabled by default for containers. If you wish to be explicit, you can also specify it manually through the `network` property:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "HOST"
    }
  },
```

For non-Docker applications, you don't need to specify anything.

#### Specifying Ports

You can specify the ports that are available through the `ports` array:

```json
    "ports": [
        0, 0, 0
    ],
```

Or through the `portDefinitions` array:

```json
    "portDefinitions": [
      {"port": 0}, {"port": 0}, {"port": 0}
    ],
```


In this example, we specify three randomly assigned host ports which would then be available to our command via the environment variables `$PORT0`, `$PORT1` and `$PORT2`. Marathon will also randomly assign three service posts in addition to these three host ports.

You can also specify specific service ports:

```json
    "ports": [
        2001, 2002, 3000
    ],
```

Or:

```json
    "portDefinitions": [
        {"port": 2001}, {"port": 2002}, {"port": 3000}
    ],
```

In this case, host ports `$PORT0`, `$PORT1` and `$PORT3` remain randomly assigned. However, the three service ports for this application are now `2001`, `2002` and `3000`.

In this example, as with the previous one, it is necessary to use a service discovery solution such as HAProxy to proxy requests from service ports to host ports.

If you want the applications service ports to be equal to its host ports, you can set `requirePorts` to `true` (`requirePorts` is `false` by default). This will tell Marathon to only schedule this application on agents which have these ports available:

```json
    "ports": [
        2001, 2002, 3000
    ],
    "requirePorts" : true
```

The service and host ports (including the environment variables `$PORT0`, `$PORT1`, and `$PORT2`), are both now `2001`, `2002` and `3000`.

This property is useful if you don't use a service discovery solution to proxy requests from service ports to host ports.


Defining the `portDefinitions` array allows you to specify a protocol, a name and labels for each port. When starting
new tasks, Marathon will pass this metadata to Mesos. Mesos will expose this information in the `discovery` field of the
task. Custom network discovery solutions can consume this field.

Example port definition requesting a dynamic `tcp` port named `http` with the label `VIP_0` set to `10.0.0.1:80`:

```json
    "portDefinitions": [
        {
            "port": 0,
            "protocol": "tcp",
            "name": "http",
            "labels": {"VIP_0": "10.0.0.1:80"}
        }
    ],
```

The `port` field is mandatory. The `protocol`, `name` and `labels` fields are optional. A port definition in which only
the `port` field is set is equivalent to an element of the `ports` array.

Note that only the `ports` array and the `portDefinitions` array should not be specified together, unless all their
elements are equivalent.

#### Referencing Ports

You can reference host ports in the Dockerfile for our fictitious app as follows:

```sh
CMD ./my-app --http-port=$PORT0 --https-port=$PORT1 --monitoring-port=$PORT2
```

Alternatively, if you aren't using Docker or had specified a `cmd` in your Marathon application definition, it works in the same way:

```json
    "cmd": "./my-app --http-port=$PORT0 --https-port=$PORT1 --monitoring-port=$PORT2"
```

### Bridge Mode

Bridge mode networking allows you to map host ports to ports inside your container and is only applicable to Docker containers. It is particularly useful if you are using a container image with fixed port assignments that you can't modify. Note that it not necessary to `EXPOSE` ports in your Dockerfile.

#### Enabling Bridge Mode

You need to specify bridge mode through the `network` property:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "BRIDGE"
    }
  },
```

#### Enabling User Mode

You need to specify user mode through the `network` property:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "USER"
    }
  },
  "ipAddress": {
    "networkName": "someUserNetwork"
  }
```

#### Specifying Ports

Port mappings are similar to passing -p into the Docker command line and specify a relationship between a port on the host machine and a port inside the container. In this case, the `portMappings` array is used **instead** of the `ports` or `portDefinitions` array used in host mode.

Port mappings are specified inside the `portMappings` object for a `container`:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 0, "hostPort": 0 },
        { "containerPort": 0, "hostPort": 0 },
        { "containerPort": 0, "hostPort": 0 }
      ]
    }
  },
```

In this example, we specify 3 mappings. A value of 0 will ask Marathon to randomly assign a value for `hostPort`. In this case, setting `containerPort` to 0 will cause it to have the same value as `hostPort`. These values are available inside the container as `$PORT0`, `$PORT1` and `$PORT2` respectively.

Alternatively, if our process running in the container had fixed ports, we might do something like the following:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 80, "hostPort": 0 },
        { "containerPort": 443, "hostPort": 0 },
        { "containerPort": 4000, "hostPort": 0 }
      ]
    }
  },
```

In this case, Marathon will randomly allocate host ports and map these to ports `80`, `443` and `4000` respectively. It's important to note that the `$PORT` variables refer to the host ports. In this case, `$PORT0` will be set to the value of `hostPort` for the first mapping and so on.

##### Specifying Protocol

You can also specify the protocol for these port mappings. The default is `tcp`:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 80, "hostPort": 0, "protocol": "tcp" },
        { "containerPort": 443, "hostPort": 0, "protocol": "tcp" },
        { "containerPort": 4000, "hostPort": 0, "protocol": "udp" }
      ]
    }
  },
```

##### Specifying Service Ports

By default, Marathon will be creating service ports for each of these ports and assigning them random values. Service ports are used by service discovery solutions and it is often desirable to set these to well known values. You can do this by setting a `servicePort` for each mapping:

```json
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "my-image:1.0",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 80, "hostPort": 0, "protocol": "tcp", "servicePort": 2000 },
        { "containerPort": 443, "hostPort": 0, "protocol": "tcp", "servicePort": 2001 },
        { "containerPort": 4000, "hostPort": 0, "protocol": "udp", "servicePort": 3000}
      ]
    }
  },
```

In this example, the host ports `$PORT0`, `$PORT1` and `$PORT3` remain randomly assigned. However, the service ports for this application are now `2001`, `2002` and `3000`. An external proxy, like HAProxy, should be configured to route from the service ports to the host ports.

#### Referencing Ports

If you set `containerPort` to 0, then you should specify ports in the Dockerfile for our fictitious app as follows:

```sh
CMD ./my-app --http-port=$PORT0 --https-port=$PORT1 --monitoring-port=$PORT2
```

However, if you've specified `containerPort` values, you simply use the same values in the Dockerfile:

```sh
CMD ./my-app --http-port=80 --https-port=443 --monitoring-port=4000
```

Alternatively, you can specify a `cmd` in your Marathon application definition, it works in the same way as before:

```json
    "cmd": "./my-app --http-port=$PORT0 --https-port=$PORT1 --monitoring-port=$PORT2"
```

Or, if you've used fixed values:

```json
    "cmd": "./my-app --http-port=80 --https-port=443 --monitoring-port=4000"
```
