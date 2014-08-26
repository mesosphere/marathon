---
title: Native Docker Containers with Mesos and Marathon
---

# Native Docker Containers with Mesos and Marathon

This document describes how to run [Docker](https://docker.com/) containers using
the native Docker support added in version `0.20.0` (released August 2014).

## Resources

- [Mesos Docker Containerizer](http://mesos.apache.org/documentation/latest/docker-containerizer)

## Prerequisites

- Mesos `0.20.0` or better
- Start all `mesos-slave` instances with the flag
  `--containerizers=docker,mesos`.
    - The order is significant!
    - Mesosphere package read config from well-known paths, so it's possible
      to specify this by doing

        ```bash
        $ echo 'docker,mesos' > /etc/mesos-slave/containerizers`
        ```

## Overview

To use the new native container support, and add a `container` field to your
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

To supply credentials to pull from a private repository, add a `.dockercfg` to
the `uris` field.

Note that initially, Mesos supports only the host (`--net=host`) Docker
networking mode.

For convenience, the mount point of the mesos sandbox is available in the
environment as `$MESOS_SANDBOX`.

## Migration from the `0.6.x` container format

Previously, the `container` field looked like this:

```json
{
  "container": {
    "image": "docker:///megacorp/product:1.2.3",
    "options": [
      "-v", "/var/data/a:/etc/a:ro",
      "-v", "/var/data/b:/etc/b:rw",
      "-e", "FOO=bar",
      "-p", "443:443"
    ]
  }
}
```

Here is the `container` field, translated to the new format:

```json
{
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "docker:///megacorp/product:1.2.3"
    },
    "volumes": [
      {
        "hostPath": "/var/data/a",
        "containerPath": "/etc/a",
        "mode": "RO"
      },
      {
        "hostPath": "/var/data/b",
        "containerPath": "/etc/b",
        "mode": "RW"
      }
    ]
  },
  "env": {
    "FOO": "bar"
  },
  "ports": [443],
  "requirePorts": true
}
```


## Advanced Usage

At a lower level, the semantics have changed slightly for the `TaskInfo`
protobuf message in Mesos.

As of `0.7.0` Marathon supports an `args` field in the app JSON.  It is
invalid to supply both `cmd` and `args` for the same app.  This change mirrors
API and semantics changes in the Mesos `CommandInfo` protobuf message starting
with version `0.20.0`.  The behavior of `cmd` is as in previous releases (the
value is wrapped by Mesos via `/bin/sh -c '${app.cmd}`).

This new (`"args"`) mode of specifying a command allows for safe usage of
containerizer features like custom Docker `ENTRYPOINT`s.  For example, given
the following Dockerfile with an `ENTRYPOINTS` defined:

```bash
FROM busybox
MAINTAINER support@mesosphere.io

CMD ["inky"]
ENTRYPOINT ["echo"]
```

Supplying the following trivial app definition will execute `echo hello`

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
