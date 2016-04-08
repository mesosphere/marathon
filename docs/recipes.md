---
title: Marathon Recipes
---

# Marathon Recipes

Below are some common patterns for specifying Marathon apps.

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

The following JSON file will make a private Docker registry available in your cluster.

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
  "ports": [0]
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

Command health checks are executed on the slave where the task is running.
To enable this feature for marathon versions prior to `0.7.4`, start Marathon with the `--executor_health_checks` flag (not required/allowed since `0.7.4`). Requires Mesos version `0.20.0` or later.

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

## Constraining task placement / static partitioning

Usually, Marathon decides where to start your application tasks without your input. This allows your infrastructure to scale easily, since all resources are treated equally.

However, there are times when you want to separate some machines from the common pool and schedule only certain tasks on them. For example:

* Some nodes in your cluster have publicly reachable network addresses and you only want to start tasks there that make use of public addresses.

* Some nodes in your cluster have special hardware, such as very fast SSDs or GPUs, that you want to reserve for tasks that need this hardware.

See the detailed docs on
<a href="{{ site.baseurl }}/docs/constraints.html">constraints</a>.
  
### Using roles

You can configure some of your Mesos agents to only offer their resources to a specific Mesos role. Only Mesos frameworks (such as Marathon) that are configured for this specific Mesos role will get offers for these resources. In this way, you can prevent accidental usage of these resources by other Mesos frameworks.

1. Configure Marathon: `--mesos_role yourrole`

2. Configure the Mesos master: `--roles=<...other roles...>,yourrole`

3. Configuring the Mesos agents whose resource offers you want to restrict. Either use the  `--default_role yourrole` flag to assign all that agent's resources to your role or use the `--resources` flag to assign individual resources to that role (such as a certain port range). See [the Mesos command line documentation](http://mesos.apache.org/documentation/latest/configuration/) for details.

### Preventing Accidental Use of Special Roles inside Marathon

With the base setup, that is, with no command-line parameters specified, all Marathon applications will by default use all resources either assigned to the unspecific "*" role or the role you specified with `--mesos_role`.

To ensure that only special tasks are run on nodes you specify, you can use a separate Marathon instance for those tasks.

You can also configure Marathon to ignore your special roles for apps by default, then explicitly configure the exceptions:

1. Configure Marathon to ignore the special resources by default: `--default_accepted_resource_roles '*'` (make sure
that you quote the "*" correctly)

2.  Configure an app to run on top of resources of your special role:

```javascript
{
    "id": "/my.special.app",
    "acceptedResourceRoles": [ "yourrole" ]
    // ... more config ...
}
```

### Limitations of Static Partitioning with Roles

* One framework can currently only be assigned to one role. So if you have multiple types of special nodes, you need to have separate frameworks for them.

* You must reconfigure your Mesos master and some of your agents in order to use this functionality.

### Using constraints

You can use constraints to restrict where to run the tasks for your apps. See
[constraints]({ site.baseurl }}/docs/constraints.html) for details.

The advantage of using constraints to restrict where tasks run is that you only have to provide appropriate attributes on the Mesos agents.

The disadvantages are that nothing prevents another framework from scheduling tasks on these agents and consuming all the resources your special tasks need. To avoid this, you need to configure Marathon so that all non-special apps are constrained *not* to use the special nodes.
