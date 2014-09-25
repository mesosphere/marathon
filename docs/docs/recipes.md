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
To enable this feature, start Marathon with the `--executor_health_checks`
flag.  Requires Mesos version `0.20.0` or later.

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
