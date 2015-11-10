---
title: Running Docker Containers on Marathon
---

# Running Docker Containers on Marathon

This document describes how to run [Docker](https://docker.com/) containers on
Marathon using the native Docker support added in Apache Mesos version 0.20.0
(released August 2014).

## Configuration

Note that DCOS clusters are already configured to run Docker containers, so 
DCOS users do not need to follow the steps below.

#### Prerequisites

+ Docker version 1.0.0 or later installed on each slave node.

#### Configure mesos-slave

  <div class="alert alert-info">
    <strong>Note:</strong> All commands below assume `mesos-slave` is being run
    as a service using the package provided by 
    <a href="http://mesosphere.com/2014/07/17/mesosphere-package-repositories/">Mesosphere</a>
  </div>

1. Update slave configuration to specify the use of the Docker containerizer
  <div class="alert alert-info">
    <strong>Note:</strong> The order of the parameters to `containerizers` is important. 
    It specifies the priority used when choosing the containerizer to launch
    the task.
  </div>

    ```bash
    $ echo 'docker,mesos' > /etc/mesos-slave/containerizers
    ```

2. Increase the executor timeout to account for the potential delay in pulling a docker image to the slave.


    ```bash
    $ echo '5mins' > /etc/mesos-slave/executor_registration_timeout
    ```

3. Restart `mesos-slave` process to load the new configuration

#### Configure Marathon

1. Increase the Marathon [command line option]({{ site.baseurl }}/docs/command-line-flags.html)
`--task_launch_timeout` to at least the executor timeout, in milliseconds, 
you set on your slaves in the previous step.


## Overview

To use the native container support, add a `container` field to your
app definition JSON:

```json
{
  "container": {
    "type": "DOCKER",
    "docker": {
      "network": "HOST",
      "image": "group/image"
    },
    "volumes": [
      {
        "containerPath": "/etc/a",
        "hostPath": "/var/data/a",
        "mode": "RO"
      },
      {
        "containerPath": "/etc/b",
        "hostPath": "/var/data/b",
        "mode": "RW"
      }
    ]
  }
}
```

where `volumes` and `type` are optional (the default type is `DOCKER`).  More
container types may be added later.

For convenience, the mount point of the Mesos sandbox is available in the
environment as `$MESOS_SANDBOX`.

### Bridged Networking Mode

_Note: Requires Mesos 0.20.1 and Marathon 0.7.1_

Bridged networking makes it easy to run programs that bind to statically
configured ports in Docker containers. Marathon can "bridge" the gap between
the port resource accounting done by Mesos and the host ports that are bound
by Docker.

**Dynamic port mapping:**

Let's begin by taking an example app definition:

```json
{
  "id": "bridged-webapp",
  "cmd": "python3 -m http.server 8080",
  "cpus": 0.5,
  "mem": 64.0,
  "instances": 2,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "python:3",
      "network": "BRIDGE",
      "portMappings": [
        { "containerPort": 8080, "hostPort": 0, "servicePort": 9000, "protocol": "tcp" },
        { "containerPort": 161, "hostPort": 0, "protocol": "udp"}
      ]
    }
  },
  "healthChecks": [
    {
      "protocol": "HTTP",
      "portIndex": 0,
      "path": "/",
      "gracePeriodSeconds": 5,
      "intervalSeconds": 20,
      "maxConsecutiveFailures": 3
    }
  ]
}
```

Here `"hostPort": 0` retains the traditional meaning in Marathon, which is "a
random port from the range included in the Mesos resource offer". The resulting
host ports for each task are exposed via the task details in the REST API and
the Marathon web UI. `"hostPort"` is optional and defaults to `0`.

"containerPort" refers to the port the application listens to inside of the container.

Since <span class="label label-default">v0.9.0</span>: `"containerPort"` is optional and now defaults to `0`.
When `"containerPort": 0`, Marathon assigns
the container port the same value as the assigned `hostPort`. This is especially useful for apps that
advertise the port they are listening on to the outside world for P2P communication. Without "containerPort": 0 they
would erroneously advertise their private container port which is usually not the same as the externally visible host
port.

`"servicePort"` is a helper port intended for doing service discovery using
a well-known port per service.  The assigned `servicePort` value is not used/interpreted by Marathon itself but
supposed to used by load balancer infrastructure.
See [Service Discovery Load Balancing doc page]({{ site.baseurl }}/docs/service-discovery-load-balancing).
The `servicePort` parameter is optional
and defaults to `0`.  Like `hostPort`, If the value is `0`, a random port will
be assigned.  If a `servicePort` value is assigned by Marathon then Marathon guarantees that its value
is unique across the cluster. The values for random service ports are in the
range `[local_port_min, local_port_max]` where `local_port_min` and
`local_port_max` are command line options with default values of `10000` and
`20000`, respectively.

The `"protocol"` parameter is optional and defaults to `"tcp"`. Its possible values are `"tcp"` and `"udp"`.

**Static port mapping:**

It's also possible to specify non-zero host ports. When doing this
you must ensure that the target ports are included in some resource offers!
The Mesos slave announces port resources in the range `[31000-32000]` by
default. This can be overridden; for example to also expose ports in the range
`[8000-9000]`:

```
--resources="ports(*):[8000-9000, 31000-32000]"
```

See the [network configuration](https://docs.docker.com/articles/networking/)
documentation for more details on how Docker handles networking.

### Using a Private Docker Repository

See the [private registry]({{ site.baseurl }}/docs/native-docker-private-registry.html)
documentation for more details on how to initiate a `docker pull` from a private docker registry
using marathon.

### Advanced Usage

#### Forcing a Docker Pull

Marathon 0.8.2 with Mesos 0.22.0 supports an option to force Docker to pull
the image before launching each task.

```json
{
  "type": "DOCKER",
  "docker": {
    "image": "group/image",
    "forcePullImage": true
  }
}
```

#### Command vs Args

As of version 0.7.0, Marathon supports an `args` field in the app JSON.  It is
invalid to supply both `cmd` and `args` for the same app.  The behavior of `cmd`
is as in previous releases (the value is wrapped by Mesos via
`/bin/sh -c '${app.cmd}`).

This new (`"args"`) mode of specifying a command allows for safe usage of
containerizer features like custom Docker `ENTRYPOINT`s.  For example, given
the following Dockerfile with an `ENTRYPOINT` defined:

```bash
FROM busybox
MAINTAINER support@mesosphere.io

CMD ["inky"]
ENTRYPOINT ["echo"]
```

Supplying the following app definition will download the public
["mesosphere/inky" Docker container](https://registry.hub.docker.com/u/mesosphere/inky/)
and execute `echo hello`:

```json
{
    "id": "inky", 
    "container": {
        "docker": {
            "image": "mesosphere/inky"
        },
        "type": "DOCKER",
        "volumes": []
    },
    "args": ["hello"],
    "cpus": 0.2,
    "mem": 32.0,
    "instances": 1
}
```

Named arguments can be passed as an array of consecutive `argc, argv` tuples,
e.g.:

```json
   "args": [
      "--name", "etcd0",
      "--initial-cluster-state", "new"
    ]
```

#### Privileged Mode and Arbitrary Docker Options

Starting with version 0.7.6, Marathon supports two new keys for docker
containers: `privileged` and `parameters`.  The `privileged` flag allows users
to run containers in privileged mode.  This flag is `false` by default.  The
`parameters` object allows users to supply arbitrary command-line options
for the `docker run` command executed by the Mesos containerizer.  Note that
any parameters passed in this manner are not guaranteed to be supported in
the future, as Mesos may not always interact with Docker via the CLI.

```json
{
    "id": "privileged-job",
    "container": {
        "docker": {
            "image": "mesosphere/inky",
            "privileged": true,
            "parameters": [
                { "key": "hostname", "value": "a.corp.org" },
                { "key": "volumes-from", "value": "another-container" },
                { "key": "lxc-conf", "value": "..." }
            ]
        },
        "type": "DOCKER",
        "volumes": []
    },
    "args": ["hello"],
    "cpus": 0.2,
    "mem": 32.0,
    "instances": 1
}
```

## Resources

- [Mesos Docker Containerizer](http://mesos.apache.org/documentation/latest/docker-containerizer)
