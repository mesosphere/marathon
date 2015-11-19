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

## Constraining task placement / static partitioning

Usually, Marathon decides where to start your application tasks without your interference.
That is desirable because it truly makes your infrastructure easily scalable. All resources are treated equally. 
However, there are times when you want
to separate some machines from the common pool and ascertain that only some special tasks are scheduled there. For
example,

* you might have some nodes with publicly reachable network addresses. You only want to start tasks there
  that make use of public addresses.
* you have some nodes with special hardware such as very fast SSDs or GPUs that you do not want to waste for
  regular tasks.
  
### Using roles

You can configure some of your Mesos agents such that they offer their resources only to a specific
Mesos role. Only Mesos frameworks (such as Marathon) that are configured for this specific Mesos role will get
offers for these resources. The advantage is that you can prevent accidental usage of these resources 
by other Mesos frameworks this way.

Configuring Marathon: `--mesos_role yourrole`

Configuring Mesos Master: `--roles=<...other roles...>,yourrole`

Configuring the Mesos Agents in question: Either use `--default_role yourrole` to assign all resources of that
agent to your role or use the `--resources` configuration to assign individual resources to that role (such
as a certain port range). See
[the Mesos command line documentation](http://mesos.apache.org/documentation/latest/configuration/) for details.

### Preventing Accidental Usage of Special Roles inside Marathon

With the base setup, all Marathon applications will by default use all resources either assigned to the 
unspecific "*" role or the role you specified with `--mesos_role`. One way to ensure that only special tasks 
are run on these nodes is to use a separate Marathon instance for these. Another way is to configure
Marathon such that it will ignore your special roles for apps by default and then explicitly configure the
exceptions as shown below.

Configuring Marathon to ignore the special resources by default: `--default_accepted_resource_roles '*'` (make sure
that you quote the "*" correctly)

Configure an app to run on top of resources of your special role:

```javascript
{
    "id": "/my.special.app",
    "acceptedResourceRoles": [ "yourrole" ]
    // ... more config ...
}
```

### Limitations of Static Partitioning with Roles

The biggest limitation is that one framework can currently only be assigned to one role. So if you have
multiple types of special nodes you cannot handle them within one framework — you need to have separate frameworks
for them. Another limitation is that you have to reconfigure your Mesos master and some of your agents.

### Using constraints

You can use constraints to restrict where to run the tasks for your apps. See 
[constraints]({ site.baseurl }}/docs/constraints.html) for details.

The advantages are that you only have to provide appropriate attributes on the Mesos agents. The disadvantages
are that nothing prevents another framework to schedule tasks on these agents and consume all resources that
you need for your special tasks. Inside of Marathon, you have to make sure that all non-special apps are
constraint to NOT use the special nodes.