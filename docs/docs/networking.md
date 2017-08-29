---
title: Networking
---

# Networking

This document describes the networking API released as of Marathon 1.5.

While Marathon continues to consume the [legacy ports API](ports.html) that was shipped in versions 1.4.x and prior, all new applications should be declared using the new, non-deprecated networking API fields that are documented here. Applications using the old networking API fields will be automatically migrated to the new networking API in Marathon 1.5.x. As of version 1.5, Marathon will only produce responses in the new 1.5 networking API format.

See the [Migrating to the 1.5 Networking API]({{ site.baseurl }}/docs/upgrade/network-api-migration.html) for more information on changes you may need to make to your applications.

### VIPs

If you are running Marathon within a [DC/OS cluster](https://dcos.io/get-started), you can use [virtual addresses (VIPs)](https://dcos.io/docs/1.8/usage/service-discovery/virtual-ip-addresses/) to make ports management easier.
VIPs simplify inter-app communication and implement a reliable service-oriented architecture.
VIPs map traffic from a single virtual address to multiple IP addresses and ports.

### Configuration

Several [command-line flags](command-line-flags.html) determine Marathon's behavior with respect to networking.

* `default_network_name` is injected as the `name` of a `container` mode network when left blank by an application.
* `local_port_min` and `local_port_max` define a port range from which Marathon automatically allocates \*service-port\*s.

## Networking Modes

Marathon apps and pods declare `networks` the same way.
Three networking modes are supported:

### `host` Networking

In `host` networking, an application shares the network namespace of the Mesos agent
process, typically the host network namespace.

### `container` Networking

An application should be allocated its own network namespace and IP address;
Mesos network isolators are responsible for providing backend support for this.
When using the Docker containerizer, this translates to a Docker "user" network.
Container networks are named, either explicitly by an application or else via `--default_network_name`.
Unnamed container networks will fail to validate if `--default_network_name` is not specified by the operator.

### `container/bridge` Networking

Similar to `container`, an application should be allocated its own network namespace and IP address;
Mesos CNI provides a special `mesos-bridge` that application containers are attached to.
When using the Docker containerizer, this translates to the Docker "default bridge" network.

**Notes**:

- All network modes are supported for both the Mesos and Docker containerizers.
- The `Network.name` parameter is only supported with `container` networking.

### Usage:

* An application can join one or more `container` mode networks, with caveats:
    * The Docker containerizer **does not support** multiple `container` mode networks (this limitation is imposed by Mesos).
    * When joining multiple container networks, additional restrictions are imposed on *port-mapping* entries (see *Port Mappings* for details).
* An application can only join one `host` mode network. This is the default if an app definition does not declare a `networks` field.
* An application can only join one `container/bridge` network.
* An application cannot mix networking modes: you must either specify a single `host` network, a single `container/bridge` network, or one or more `container` networks.

## Ports for Marathon Apps

Marathon apps declare ports differently than pods.
The following section is only relevant to Marathon apps.

### Terminology

#### Port Types

##### *container-port*:

Specifies a port within a container.
`containerPort` is specified as a field of a *port-mapping* or *endpoint* when using `container` or `container/bridge` mode networking.

##### *host-port*:

Specifies a port to allocate from the resources offered by a Mesos agent.
`hostPort` is specified as a field of a *port-mapping* or *endpoint* when using `container` or `container/bridge` mode networking.
`port` is specified as a field of a *port-definition* when using `host` mode networking.

**Note:** Only host ports are made available to a task through environment variables.

##### *service-port*:

When you create a new application in Marathon (either through the REST API or the front end), you may assign one or more service ports to it.
You can specify all valid port numbers as service ports, or you can use `0` to indicate that Marathon should allocate free service ports to the app automatically.
Marathon allocates service ports from the range defined by the `--local_port_min` and `--local_port_max` command line flags.
If you choose your own service port, you must ensure that it is unique across all of your applications.

See [the port definition section](#port-definition) for more information.

**Note:** Pods (endpoints) do not support service ports.

#### Declaring ports in an application

##### *endpoint*:

Endpoints are declared only by the containers of a Pod.
See the documentation for [pods](pods.html).

##### *port-definition*:

Port-definitions are used only with `host` mode networking.
A *port-definition* (specifically its `port` field) is interpreted through the lens of the `requirePorts` app field:

* When `requirePorts` is `false` (default), a port-definition's `port` is considered the *service-port* and a *host-port* is dynamically chosen by Marathon.
* When `requirePorts` is `true`, a port-definition's `port` is considered both a *host-port* and *service-port*, otherwise;
* The special `port` value of `0` tells Marathon to select any *host-port* from a Mesos resource offer and any *service-port* from the configured service port range.

##### *port-mapping*:

A *port-mapping* declares a *container-port* for an application, possibly linking that *container-port* to a *host-port* and *service-port*.
Marathon communicates *container-port*/*host-port* links (aka "mappings") to Mesos when launching instances of the application.
Port-mappings are used with both `container` and `container/bridge` networking.
Marathon ignores the value of `requirePorts` when interpreting a *port-mapping*.
* The special `containerPort` value of `0` tells Marathon to internally assign the (eventually) allocated *host-port* to `containerPort`.
* The special `hostPort` value of `0` tells Marathon to select any *host-port* from a Mesos resource offer.
* The special `servicePort` value of `0` tells Marathon to select any *service-port* from the configured *service-port* range.

### Port Definitions

#### Summary

* Review *port-definition*, *host-port*, and *service-port* in (Terminology)[#Terminology].
* Location in app definition: `{ "portDefinitions": [ <port-definition>... ], "requirePorts": <bool>, ... }`
* Used in conjunction with `host` mode networking.
* `requirePorts` applies to `portDefinitions`.
* If no `portDefinitions` are defined (or defined as `null`) at create-time, default to `{ "portDefinitions": [ { "port": 0, "name": "default" } ], ... }`
    * Specify an empty array (`[]`) to indicate NO ports are used by the app; no default is injected in this case.
* Ignored when used in conjunction with other networking modes.
    * NOTE: Future versions of Marathon may fail to validate apps that declare `portDefinitions` with network modes other than `host`.

### Port Mappings

Summary:

* Review *port-mapping*, *container-port*, and *host-port* in (Terminology)[#Terminology].
* Location in app definition: `{ "container": { "portMappings": [ <port-mapping>... ], ... }, ... }`
* Used in conjunction with `container` and `container/bridge` mode networking.
* When using `container/bridge` mode networking, an unspecified (`null`) value for `hostPort` is translated to `"hostPort": 0`.
* `requirePorts` does not apply to `portMappings`.
* If unspecified (`null`) at create-time, defaults to `{ "portMappings": [ { "containerPort": 0, "name": "default" } ], ... }`
    * Specify an empty array (`[]`) to indicate NO ports are used by the app; no default is injected in this case.
    * **NOTE:** When using `container/bridge` mode, the default *port-mapping* also sets `"hostPort: 0"`.
* Ignored when used in conjunction with other networking modes.
    * **NOTE:** Future versions of Marathon may fail to validate apps that declare `container.portMappings` with network modes other than `container` or `container/bridge`.
* When used in conjunction with multiple container networks, each mapping entry that specifies a `hostPort` must also declare a `networkNames` value with a single item, identifying the network for which the mapping applies (a single `hostPort` may be mapped to only one container network, and `networkNames` defaults to all container networks for a pod or app).

## Downward API

### Per-Task Environment Variables

If a port is named `NAME`, it will be accessible via the environment variable `$PORT_NAME`.
Every *host-port* value is also exposed to the running application instance via environment variables `$PORT0`, `$PORT1`, etc.
Each Marathon application is given a single port by default, so `$PORT0` will normally be available, except for apps that specifically declare "no ports".
Variables are generated for all apps, regardless of whether the app uses the Mesos or Docker containerizer.
It is **highly recommended** to name the ports of an app to provide clarity with respect to the app configuration and intended use of each port.

When using `container` or `container/bridge` mode networking, be sure to bind your application to the `containerPort`s you have specified in your `portMapping`s.
If you have set `containerPort` to `0`, this will be the same as `hostPort` and you can use the `$PORTxxx` environment variables.

Additional [per-task enviroment variables](task-environment-variables.html) are also provided.

### Discovery Via Mesos

#### DiscoveryInfo and port `labels`:

* `labels` may be defined for items of `portDefinitions` as well as for items of `portMappings`. These labels are sent to Mesos via `DiscoveryInfo` protobufs at instance-launch time.
* Given a mapping or endpoint, we generate a port `DiscoveryInfo` for every combination of specified protocols and associated networks. (IE, if an `Endpoint` specifies 2 protocols and is associated with 3 container networks, then a total of 6 `DiscoveryInfo` protobufs would be generated for that single `Endpoint`).
* Marathon injects a `network-scope` label into the port `DiscoveryInfo` to disambiguate between a *host-port* and *container-port*.
    * A scope value of `host` is used for *host-port* discovery.
    * A scope value of `container` is used for *container-port* discovery.
* For *container-port* discovery, Marathon also injects a `network-name` label into the respective port `DiscoveryInfo`.

#### Virtual addresses

See the DC/OS documentation for [virtual addresses (VIPs)](https://dcos.io/docs/1.8/usage/service-discovery/virtual-ip-addresses/).

## Examples

### `host` Mode

`host` mode networking is the default networking mode for all apps.
If your app uses Docker containers, it not necessary to `EXPOSE` ports in your `Dockerfile`.

#### Using `host` Mode

Host mode is enabled by default for all apps and all container types.
If you wish to be explicit, you can also specify it manually through the `networks` property:

```json
  "networks": [ { "mode": "host" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    }
  },
```

#### Specifying Ports

You can specify the ports that are available through the `portDefinitions` array:

```json
    "portDefinitions": [
      {"port": 0, "name": "http"}, {"port": 0, "name": "https"}, {"port": 0, "name": "mon"}
    ],
```


In this example, we specify three dynamically assigned host ports, which would then be available to our command via the environment variables `$PORT_HTTP`, `$PORT_HTTPS` and `$PORT_MON`.
Marathon will also associate three dynamically selected service ports to these three host ports.

You can also specify specific service ports:

```json
    "portDefinitions": [
        {"port": 2001, "name": "http"}, {"port": 2002, "name": "https"}, {"port": 3000, "name": "mon"}
    ],
```

In this case, host ports `$PORT_HTTP`, `$PORT_HTTPS` and `$PORT_MON` remain dynamically assigned.
However, the three service ports for this application are now `2001`, `2002` and `3000`.

In this example, as with the previous one, it is necessary to use a service discovery solution such as HAProxy to proxy requests from service ports to host ports.

If you want the application's service ports to be equal to its host ports, you can set `requirePorts` to `true` (`requirePorts` is `false` by default).
This will tell Marathon to only schedule this application on agents that have these ports available:

```json
    "portDefinitions": [
        {"port": 2001, "name": "http"}, {"port": 2002, "name": "https"}, {"port": 3000, "name": "mon"}
    ],
    "requirePorts" : true
```

The service and host ports (including the environment variables `$PORT_HTTP`, `$PORT_HTTPS`, and `$PORT_MON`), are both now `2001`, `2002` and `3000`.

This property is useful if you don't use a service discovery solution to proxy requests from service ports to host ports.


Each *port-definition* in a `portDefinitions` array allows you to specify a `protocol`, a `name` and `labels` for each definition.
When starting new tasks, Marathon will pass this metadata to Mesos.
Mesos will expose this information in the `discovery` field of the task.
Custom network discovery solutions can consume this field.

Example *port-definition* requesting a dynamic `tcp` port named `http` with the label `VIP_0` set to `10.0.0.1:80`:

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

The `port` field is mandatory.
The `protocol`, `name` and `labels` fields are optional.

#### Referencing Ports

You can reference the *host-port*s in the Dockerfile for our fictitious app as follows:

```sh
CMD ./my-app --http-port=$PORT_HTTP --https-port=$PORT_HTTPS --monitoring-port=$PORT_MON
```

Alternatively, if you are not using Docker, or had specified a `cmd` in your Marathon application definition, it works in the same way:

```json
    "cmd": "./my-app --http-port=$PORT_HTTP --https-port=$PORT_HTTPS --monitoring-port=$PORT_MON"
```

### `container` and `container/bridge` Mode

Bridge mode networking allows you to map host ports to ports inside your containers.
It is particularly useful if you are using a container image with fixed port assignments that you can't modify.

**Note:** It is not necessary to `EXPOSE` ports in your Dockerfile when using Docker container images.

#### Enabling `container/bridge` Mode

Specify `container/bridge` mode through the `networks` property:

```json
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    }
  },
```
##### Installing the `mesos-bridge` CNI plugin
If you are not using DC/OS and want to enable `container/bridge` mode with the Universal Container Runtime (UCR), several more steps are necessary to install and use the `mesos-bridge` CNI plugin.

**Prerequisites**
- Mesos version 1.2.0 or higher.
- Marathon version 1.5 or higher.

1. Clone the CNI repository from [https://github.com/containernetworking/cni](https://github.com/containernetworking/cni) and build according to their instructions.

1. Navigate to or create a `/var/lib/mesos/cni` directory on each of your agent nodes as well as `config` and `plugins` subdirectories.

1. Copy the contents of the `bin` folder created in the previous step to `/var/lib/mesos/cni/plugins` on each agent node.

1. Create a file called `mesos-bridge.json`, copy the following configuration into it, and add it to `/var/lib/mesos/cni/config`.

   ```
   {
     "name": "mesos-bridge",
     "type": "mesos-cni-port-mapper",
     "excludeDevices": ["mesos-bridge"],
     "chain": "MESOS-BRIDGE-PORT-MAPPER",
     "delegate": {
       "type": "bridge",
       "bridge": "mesos-bridge",
       "isGateway": true,
       "ipMasq": true,
       "ipam": {
         "type": "host-local",
         "subnet": "10.1.1.0/24",
         "routes": [{
           "dst": "0.0.0.0/0"
         }]
       }
     }
   }
   ```

1. Soft-link the `mesos-cni-port-mapper` plugin to your plugins directory using the following command
   ```
   $ sudo ln -sf /usr/libexec/mesos/mesos-cni-port-mapper /var/lib/mesos/cni/plugins/mesos-cni-port-mapper
   ```

#### Enabling `container` Mode

Specify `container` mode through the `network` property:

```json
  "networks": [ { "mode": "container", "name": "someUserNetwork" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    }
  }
```

If there is a `--default_network_name` configured for Marathon, then specifying a network name for the `container` network is optional:
`container` networks with an unspecified (`null`) `name` will inherit the value of the `--default_network_name` flag.

#### Specifying Ports

Port mappings are similar to passing `-p` into the Docker command line and specify a relationship between a port on the host machine and a port inside the container.
In this case, the `portMappings` array is used **instead** of the `portDefinitions` array used in host mode.

Port mappings are specified inside a `container` object:

```json
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    },
    "portMappings": [
      { "containerPort": 0, "hostPort": 0, "name": "http" },
      { "containerPort": 0, "hostPort": 0, "name": "https" },
      { "containerPort": 0, "hostPort": 0, "name": "mon" }
    ]
  }
```

In this example, we specify 3 mappings.
A value of `0` will ask Marathon to dynamically assign a value for `hostPort`.
In this case, setting `containerPort` to `0` will cause it to have the same value as `hostPort`.
These values are available inside the container as `$PORT_HTTP`, `$PORT_HTTPS` and `$PORT_MON` respectively.

Alternatively, if our process running in the container had fixed ports, we might do something like the following:

```json
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    },
    "portMappings": [
      { "containerPort": 80, "hostPort": 0, "name": "http" },
      { "containerPort": 443, "hostPort": 0, "name": "https" },
      { "containerPort": 4000, "hostPort": 0, "name": "mon" }
    ]
  }
```

In this case, Marathon will randomly allocate host ports and map these to ports `80`, `443` and `4000` respectively.
The `$PORT_xxx` variables refer to the host ports.
In this case, `$PORT_HTTP` will be set to the value of `hostPort` for the first mapping, and so on.

##### Specifying Protocol

You can also specify the protocol for these port mappings.
The default is `tcp`:

```json
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    },
    "portMappings": [
      { "containerPort": 80, "hostPort": 0, "name": "http", "protocol": "tcp" },
      { "containerPort": 443, "hostPort": 0, "name": "https", "protocol": "tcp" },
      { "containerPort": 4000, "hostPort": 0, "name": "mon", "protocol": "udp" }
    ]
  }
```

##### Specifying Service Ports

By default, Marathon will create associated service ports for each of these declared mappings and dynamically assign them values.
Service ports are used by service discovery solutions and it is often desirable to set these to well known values.
You can assign well-known *service-port* values by defining a `servicePort` for each mapping:

```json
  "networks": [ { "mode": "container/bridge" } ],
  "container": {
    "type": "MESOS",
    "docker": {
      "image": "my-image:1.0"
    },
    "portMappings": [
      { "containerPort": 80, "hostPort": 0, "name": "http", "protocol": "tcp", "servicePort": 2000 },
      { "containerPort": 443, "hostPort": 0, "name": "https", "protocol": "tcp", "servicePort": 2001 },
      { "containerPort": 4000, "hostPort": 0, "name": "mon", "protocol": "udp", "servicePort": 3000 }
    ]
  },
```

In this example, the host ports `$PORT_HTTP`, `$PORT_HTTPS` and `$PORT_MON` remain dynamically assigned.
However, the service ports for this application are now `2001`, `2002` and `3000`.
An external proxy, like HAProxy, may be configured to route from the service ports to the host ports.

#### Referencing Ports

If you set `containerPort` to `0`, then you should specify ports in the Dockerfile for our fictitious app as follows:

```sh
CMD ./my-app --http-port=$PORT_HTTP --https-port=$PORT_HTTPS --monitoring-port=$PORT_MON
```

However, if you have defined non-zero `containerPort` values, simply use the same values in the Dockerfile:

```sh
CMD ./my-app --http-port=80 --https-port=443 --monitoring-port=4000
```

Alternatively, you can specify a `cmd` in your Marathon application definition, it works in the same way as before:

```json
"cmd": "./my-app --http-port=$PORT_HTTP --https-port=$PORT_HTTPS --monitoring-port=$PORT_MON"
```

Or, if you've used fixed values:

```json
"cmd": "./my-app --http-port=80 --https-port=443 --monitoring-port=4000"
```
