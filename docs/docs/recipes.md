---
title: Marathon Recipes
---

# Marathon Recipes

Here we outline some common patterns for specifying Marathon apps.

## Docker

See the detailed docs on
<a href="{{ site.baseurl }}/docs/native-docker.html">Docker Containers</a>.

### Simple Docker Example

```json
{
    "id": "simple-docker", 
    "container": {
      "docker": {
        "image": "busybox"
      }
    },
    "cmd": "echo hello from docker",
    "cpus": 0.2,
    "mem": 32.0,
    "instances": 2
}
```

### Docker with entry point

```json
{
    "id": "simple-docker", 
    "container": {
      "docker": {
        "image": "mesosphere/inky"
      }
    },
    "args": ["hello", "from", "docker"],
    "cpus": 0.2,
    "mem": 32.0,
    "instances": 2
}
```

where the "mesosphere/inky" Dockerfile is defined as:

```
FROM busybox
MAINTAINER support@mesosphere.io

CMD ["inky"]
ENTRYPOINT ["echo"]
```


### Start a private docker registry

This will make a private docker registry available in your cluster.
Note: this will persist all data on the local file system, which is not ideal.
Either restrict the service to run always on the same node with 
<a href="{{ site.baseurl }}/docs/constraints.html">Constraints</a> or use another persistence backend.
See [docker-registry](https://github.com/docker/docker-registry) for a list of possible backends.


```json
{
  "id": "/docker/registry",
  "instances": 1,
  "cpus": 0.5,
  "mem": 1024.0,
  "disk": 128,
  "container": {
    "docker": {
      "type": "DOCKER",
      "image": "registry:latest",
      "network": "BRIDGE",
      "parameters": [],
      "portMappings": [
        {
          "containerPort": 5000,
          "hostPort": 0,
          "protocol": "tcp",
          "servicePort": 5000
        }
      ]
    },
    "volumes": [
      {
        "hostPath": "/local/path/to/store/packages",
        "containerPath": "/storage",
        "mode": "RW"
      }
    ]
  },
  "env": {
    "SETTINGS_FLAVOR": "local",
    "STORAGE_PATH": "/storage"
  },
  "ports": [ 0 ]
}
```


## Health Checks

See the detailed docs on
<a href="{{ site.baseurl }}/docs/health-checks.html">Health Checks</a>.

### HTTP health checks

```json
{
  "id": "toggle",
  "cmd": "python toggle.py $PORT0",
  "cpus": 0.2,
  "disk": 0.0,
  "healthChecks": [
    {
      "protocol": "HTTP",
      "path": "/health",
      "portIndex": 0,
      "gracePeriodSeconds": 5,
      "intervalSeconds": 10,
      "timeoutSeconds": 10,
      "maxConsecutiveFailures": 3
    }
  ],
  "instances": 2,
  "mem": 32.0,
  "ports": [0],
  "uris": ["http://downloads.mesosphere.com/misc/toggle.tgz"]
}
```

### TCP health checks

```json
{
  "id": "toggle",
  "cmd": "python toggle.py $PORT0",
  "cpus": 0.2,
  "disk": 0.0,
  "healthChecks": [
    {
      "protocol": "TCP",
      "portIndex": 0,
      "gracePeriodSeconds": 5,
      "intervalSeconds": 10,
      "timeoutSeconds": 10,
      "maxConsecutiveFailures": 3
    }
  ],
  "instances": 2,
  "mem": 32.0,
  "ports": [0],
  "uris": ["http://downloads.mesosphere.com/misc/toggle.tgz"]
}
```

### Command (executor) health checks

These health checks are executed on the slave where the task is running.
To enable this feature for marathon versions prior to `0.7.4`, start 
Marathon with the `--executor_health_checks` flag (not required/allowed 
since `0.7.4`).  Requires Mesos version `0.20.0` or later.

```json
{
  "id": "toggle",
  "cmd": "python toggle.py $PORT0",
  "cpus": 0.2,
  "disk": 0.0,
  "healthChecks": [
    {
      "protocol": "COMMAND",
      "command": { "value": "curl -f http://$HOST:$PORT0/" },
      "gracePeriodSeconds": 5,
      "intervalSeconds": 10,
      "timeoutSeconds": 10,
      "maxConsecutiveFailures": 3
    }
  ],
  "instances": 2,
  "mem": 32.0,
  "ports": [0],
  "uris": ["http://downloads.mesosphere.com/misc/toggle.tgz"]
}
```
