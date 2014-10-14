---
title: Running Docker Containers on Marathon
---

# Running Docker Containers on Marathon

This document describes how to run [Docker](https://docker.com/) containers using
the native Docker support added in Apache Mesos version 0.20.0
(released August 2014).

### Prerequisites

## Docker

Docker version 1.0.0 or later installed on each slave node.

### Configure mesos-slave

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

### Configure marathon

1. Increase the marathon [command line option]({{ site.baseurl }}/docs/command-line-flags.html") `--task_launch_timeout` to at least the executor timeout you set on your slaves in the previous step.

### Resources

- [Mesos Docker Containerizer](http://mesos.apache.org/documentation/latest/docker-containerizer)

## Overview

To use the native container support, add a `container` field to your
app definition JSON:

```json
{
  "container": {
    "type": "DOCKER",
    "docker": {
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

  <div class="alert alert-info">
    <strong>Note:</strong> Initially, Mesos supports only the host (`--net=host`) Docker
    networking mode.
  </div>

For convenience, the mount point of the mesos sandbox is available in the
environment as `$MESOS_SANDBOX`.  The `$HOME` environment variable is set
by default to the same value as `$MESOS_SANDBOX`.

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

`"servicePort"` is a helper port intended for doing service discovery using
a well-known port per service.  The `servicePort` parameter is optional
and defaults to `0`.  Like `hostPort`, If the value is `0`, a random port will
be assigned.  The values for random service ports are in the
range `[local_port_min, local_port_max]` where `local_port_min` and
`local_port_max` are command line options with default values of `10000` and
`20000`, respectively.

The `"protocol"` parameter is optional and defaults to `"tcp"`.

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

### Using a private Docker Repository

To supply credentials to pull from a private repository, add a `.dockercfg` to
the `uris` field of your app.

### Advanced Usage

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
