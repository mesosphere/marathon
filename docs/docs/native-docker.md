---
title: Running Docker Containers on Marathon
---

# Running Docker Containers on Marathon

This document describes how to run [Docker](https://docker.com/) containers using
the native Docker support added in Apache Mesos version 0.20.0
(released August 2014).

### Prerequisites

- Mesos version 0.20.0 or later
- Docker version 1.0.0 or later installed on each Mesos slave
- Configure Mesos slaves
  - Set the containerizers `--containerizers=docker,mesos`.
    - _**The order of the containerizers is significant!**_
    - Mesosphere packages read config from well-known paths, so it's possible
      to specify this by doing

        ```bash
        $ echo 'docker,mesos' > /etc/mesos-slave/containerizers
        ```

  - Increase the executor timeout `--executor_registration_timeout=5mins`
    - Mesosphere packages read config from well-known paths, so it's possible
      to specify this by doing

        ```bash
        $ echo '5mins' > /etc/mesos-slave/executor_registration_timeout
        ```


### Resources

- [Mesos Docker Containerizer](http://mesos.apache.org/documentation/latest/docker-containerizer)

## Overview

To use the new native container support, add a `container` field to your
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

Note that initially, Mesos supports only the host (`--net=host`) Docker
networking mode.

For convenience, the mount point of the mesos sandbox is available in the
environment as `$MESOS_SANDBOX`.  The `$HOME` environment variable is set
by default to the same value as `$MESOS_SANDBOX`.

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
