##### POST `/v2/apps`

Create and start a new application.

Here is an example of an application JSON which includes all fields.

```javascript
{
    "id": "/product/service/myApp",
    "cmd": "env && sleep 300",
    "args": ["/bin/sh", "-c", "env && sleep 300"]
    "cpus": 1.5,
    "mem": 256.0,
    "portDefinitions": [
        { "port": 8080, "protocol": "tcp", "name": "http", labels: { "VIP_0": "10.0.0.1:80" } },
        { "port": 9000, "protocol": "tcp", "name": "admin" }
    ],
    "requirePorts": false,
    "instances": 3,
    "executor": "",
    "container": {
        "type": "DOCKER",
        "docker": {
            "image": "group/image",
            "network": "BRIDGE",
            "portMappings": [
                {
                    "containerPort": 8080,
                    "hostPort": 0,
                    "servicePort": 9000,
                    "protocol": "tcp"
                },
                {
                    "containerPort": 161,
                    "hostPort": 0,
                    "protocol": "udp"
                }
            ],
            "privileged": false,
            "parameters": [
                { "key": "a-docker-option", "value": "xxx" },
                { "key": "b-docker-option", "value": "yyy" }
            ]
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
    },
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    },
    "constraints": [
        ["attribute", "OPERATOR", "value"]
    ],
    "acceptedResourceRoles": [ /* since 0.9.0 */
        "role1", "*"
    ],
    "labels": {
        "environment": "staging"
    },
    "fetch": [
        { "uri": "https://raw.github.com/mesosphere/marathon/master/README.md" },
        { "uri": "https://foo.com/archive.zip", "executable": false, "extract": true, "cache": true }
    ],
    "dependencies": ["/product/db/mongo", "/product/db", "../../db"],
    "healthChecks": [
        {
            "protocol": "HTTP",
            "path": "/health",
            "gracePeriodSeconds": 3,
            "intervalSeconds": 10,
            "portIndex": 0,
            "timeoutSeconds": 10,
            "maxConsecutiveFailures": 3
        },
        {
            "protocol": "HTTPS",
            "path": "/machinehealth",
            "gracePeriodSeconds": 3,
            "intervalSeconds": 10,
            "port": 3333,
            "timeoutSeconds": 10,
            "maxConsecutiveFailures": 3
        },
        {
            "protocol": "TCP",
            "gracePeriodSeconds": 3,
            "intervalSeconds": 5,
            "portIndex": 1,
            "timeoutSeconds": 5,
            "maxConsecutiveFailures": 3
        },
        {
            "protocol": "COMMAND",
            "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" },
            "maxConsecutiveFailures": 3
        }
    ],
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "maxLaunchDelaySeconds": 3600,
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5,
        "maximumOverCapacity": 0.2
    }
}
```

##### id (String)

Unique identifier for the app consisting of a series of names separated by slashes.
Each name must be at least 1 character and may
only contain digits (`0-9`), dashes (`-`), dots (`.`), and lowercase letters
(`a-z`). The name may not begin or end with a dash.

The allowable format is represented by the following regular expression
`^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])$`

##### cmd (String)

The command that is executed.  This value is wrapped by Mesos via `/bin/sh -c ${app.cmd}`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

##### args (Array of Strings)

An array of strings that represents an alternative mode of specifying the command to run. This was motivated by safe usage of containerizer features like a custom Docker ENTRYPOINT. This args field may be used in place of cmd even when using the default command executor. This change mirrors API and semantics changes in the Mesos CommandInfo protobuf message starting with version `0.20.0`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

##### cpus (Float)

The number of CPU`s this application needs per instance. This number does not have to be integer, but can be a fraction.

##### mem (Float)

The amount of memory in MB that is needed for the application per instance.

##### ports (Array of Integers)
Since <span class="label label-default">v0.16.0</span>: __Deprecated__ . Use portDefinitions instead.

An array of required port resources on the host.

The port array currently serves multiple roles:

* The number of items in the array determines how many dynamic ports are allocated
  for every task.
* For every port that is zero, a globally unique (cluster-wide) port is assigned and
  provided as part of the app definition to be used in load balancing definitions.
  See [Service Discovery Load Balancing doc page]({{ site.baseurl }}/docs/service-discovery-load-balancing.html)
  for details.

Since this is confusing, we recommend to configure ports assignment for Docker
containers for `BRIDGE` networking in `container.docker.portMappings` instead, see
[Docker Containers doc page]({{ site.baseurl }}/docs/native-docker.html#bridged-networking-mode)).

Alternatively or if you use the Mesos Containerizer, pass zeros as port values to generate one or more arbitrary
free ports for each application instance.
Each port value is exposed to the instance via environment variables
`$PORT0`, `$PORT1`, etc. Ports assigned to running instances are also available
via the task resource.

We will probably provide an alternative way to configure this for non-Docker apps in the future
as well, see [Rethink ports API](https://github.com/mesosphere/marathon/issues/670).

##### portsDefinitions (Array of Objects)

Since <span class="label label-default">v0.16.0</span>:

An array of required port resources on the host.

The portDefinitions array currently serves multiple roles:

* The number of items in the array determines how many dynamic ports are allocated
  for every task.
* For every port that is zero, a globally unique (cluster-wide) port is assigned and
  provided as part of the app definition to be used in load balancing definitions.
  See [Service Discovery Load Balancing doc page]({{ site.baseurl }}/docs/service-discovery-load-balancing.html)
  for details.

Since this is confusing, we recommend to configure ports assignment for Docker containers for `BRIDGE` networking in
`container.docker.portMappings` instead, see [Docker Containers doc page]({{ site.baseurl }}/docs/native-docker.html#bridged-networking-mode)).

Alternatively or if you use the Mesos Containerizer, pass zeros as port values to generate one or more arbitrary
free ports for each application instance.

Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc. Ports assigned to running
instances are also available via the task resource.

We will probably provide an alternative way to configure this for non-Docker apps in the future
as well, see [Rethink ports API](https://github.com/mesosphere/marathon/issues/670).

##### requirePorts (Boolean)

Normally, the host ports of your tasks are automatically assigned. This corresponds to the
`requirePorts` value `false` which is the default.

If you need more control and want to specify your host ports in advance, you can set `requirePorts` to `true`. This way
the ports you have specified are used as host ports. That also means that Marathon can schedule the associated tasks
only on hosts that have the specified ports available.

##### instances (Integer)

The number of instances of this application to start. Please note: this number can be changed everytime as needed to scale the application.

##### executor (String)

The executor to use to launch this application. Different executors are available. The simplest one (and the default if none is given) is //cmd, which takes the cmd and executes that on the shell level.

##### container (Object)

Additional data passed to the containerizer on application launch.  These
consist of a type, zero or more volumes, and additional type-specific options.
Volumes and type are optional (the default type is DOCKER).  In order to make
use of the docker containerizer, specify `--containerizers=docker,mesos` to
the Mesos slave.  For a discussion of docker-specific options, see the
[native docker document]({{site.baseurl}}/docs/native-docker.html).

##### env (Object with String values)

Key value pairs that get added to the environment variables of the process to start.

##### constraints

Valid constraint operators are one of ["UNIQUE", "CLUSTER",
"GROUP_BY"]. For additional information on using placement constraints see
the [Constraints doc page]({{ site.baseurl }}/docs/constraints.html).

##### acceptedResourceRoles <span class="label label-default">v0.9.0</span>

Optional. A list of resource roles. Marathon considers only resource offers with roles in this list for launching
tasks of this app. If you do not specify this, Marathon considers all resource offers with roles that have been
configured by the `--default_accepted_resource_roles` command line flag. If no `--default_accepted_resource_roles` was
given on startup, Marathon considers all resource offers.

Example 1: `"acceptedResourceRoles": [ "production", "*" ]` Tasks of this app definition are launched either
on "production" or "*" resources.

Example 2: `"acceptedResourceRoles": [ "public" ]` Tasks of this app definition are launched only on "public"
resources.

Background: Mesos can assign roles to certain resource shares. Frameworks which are not explicitly registered for
a role do not see resources of that role. In this way, you can reserve resources for frameworks. Resources not reserved
for custom role, are available for all frameworks. Mesos assigns the special role "*" to them.

To register Marathon for a role, you need to specify the `--mesos_role` command line flag on startup.
If you want to assign all resources of a
slave to a role, you can use the `--default_role` argument when starting up the slave. If you need a more
fine-grained configuration, you can use the `--resources` argument to specify resource shares per role. The Mesos master
needs to be started with `--roles` followed by a comma-separated list of all roles you want to use across your cluster.
See
[the Mesos command line documentation](http://mesos.apache.org/documentation/latest/configuration/) for details.

##### labels (Object of String values)

Attaching metadata to apps can be useful to expose additional information
to other services, so we added the ability to place labels on apps
(for example, you could label apps "staging" and "production" to mark
services by their position in the pipeline).

##### fetch (Array of Objects)

Since <span class="label label-default">v0.15.0</span>: The list of URIs to
fetch before the task starts.
Example:  `{ "uri": "http://get.me" }`
Example:  `{ "uri": "https://foo.com/archive.zip", "executable": false, "extract": true, "cache": true }`
On every artifact you can define the following properties:

* `uri`: URI to be fetched by Mesos fetcher module
* `executable`: Set fetched artifact as executable
* `extract`: Extract fetched artifact if supported by Mesos fetcher module
* `cache`: Cache fetched artifact if supported by Mesos fetcher module

For documentation about the mesos fetcher cache, see here: http://mesos.apache.org/documentation/latest/fetcher/

##### uris (Array of Strings)

Since <span class="label label-default">v0.15.0</span>: __Deprecated__ . Use fetch instead.
URIs defined here are resolved, before the application gets started. If the application has external dependencies, they should be defined here.

##### dependencies (Array of Strings)

A list of services upon which this application depends. An order is derived from the dependencies for performing start/stop and upgrade of the application.  For example, an application `/a` relies on the services `/b` which itself relies on `/c`. To start all 3 applications, first `/c` is started than `/b` than `/a`.

##### healthChecks

An array of checks to be performed on running tasks to determine if they are
operating as expected. Health checks begin immediately upon task launch.
By default, health checks are executed by the Marathon scheduler.
In this case, the only supported protocol is `COMMAND` and each app is limited to
at most one defined health check.

An HTTP health check is considered passing if (1) its HTTP response code is between
200 and 399, inclusive, and (2) its response is received within the
`timeoutSeconds` period.

If a task fails more than `maxConsecutiveFailures`
health checks consecutively, that task is killed causing Marathon to start
more instances. These restarts are modulated like any other failing app
by `backoffSeconds`, `backoffFactor` and `maxLaunchDelaySeconds`.
The kill of the unhealthy task is signalled via `unhealthy_task_kill_event` event.

###### Health Check Options

* `command`: Command to run in order to determine the health of a task.
* `gracePeriodSeconds` (Optional. Default: 15): Health check failures are
  ignored within this number of seconds of the task being started or until the
  task becomes healthy for the first time.
* `intervalSeconds` (Optional. Default: 10): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3) : Number of consecutive health
  check failures after which the unhealthy task should be killed.
* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP", "HTTPS", "TCP", or "Command".
* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".
  _Note: only used if `protocol == "HTTP"`._
* `portIndex` (Optional. Default: 0): Index in this app's `ports` array to be
  used for health requests. An index is used so the app can use random ports,
  like "[0, 0, 0]" for example, and tasks could be started with port environment
  variables like `$PORT1`.
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

##### backoffSeconds, backoffFactor and maxLaunchDelaySeconds

Configures exponential backoff behavior when launching potentially sick apps.
This prevents sandboxes associated with consecutively failing tasks from
filling up the hard disk on Mesos slaves. The backoff period is multiplied by
the factor for each consecutive failure until it reaches maxLaunchDelaySeconds.
This applies also to tasks that are killed due to failing too many health checks.

##### upgradeStrategy

During an upgrade all instances of an application get replaced by a new version.
The upgradeStrategy controls how Marathon stops old versions and launches
new versions. It consists of two values:

* `minimumHealthCapacity` (Optional. Default: 1.0) - a number between `0`and `1`
that is multiplied with the instance count. This is the minimum number of healthy
nodes that do not sacrifice overall application purpose. Marathon will make sure,
during the upgrade process, that at any point of time this number of healthy
instances are up.
* `maximumOverCapacity` (Optional. Default: 1.0) - a number between `0` and
`1` which is multiplied with the instance count. This is the maximum number of
additional instances launched at any point of time during the upgrade process.

The default `minimumHealthCapacity` is `1`, which means no old instance can be
stopped before another healthy new version is deployed.
A value of `0.5` means that during an upgrade half of the old version instances
are stopped first to make space for the new version.
A value of `0` means take all instances down immediately and replace with the
new application.

The default `maximumOverCapacity` is `1`, which means that all old and new
instances can co-exist during the upgrade process.
A value of `0.1` means that during the upgrade process 10% more capacity than
usual may be used for old and new instances.
A value of `0.0` means that even during the upgrade process no more capacity may
be used for the new instances than usual. Only when an old version is stopped,
a new instance can be deployed.

If `minimumHealthCapacity` is `1` and `maximumOverCapacity` is `0`, at least
one additional new instance is launched in the beginning of the upgrade process.
When it is healthy, one of the old instances is stopped. After it is stopped,
another new instance is started, and so on.

A combination of `minimumHealthCapacity` equal to `0.9` and
`maximumOverCapacity` equal to `0` results in a rolling update, replacing
10% of the instances at a time, keeping at least 90% of the app online at any
point of time during the upgrade.

A combination of `minimumHealthCapacity` equal to `1.0` and
`maximumOverCapacity` equal to `0.1` results in a rolling update, replacing
10% of the instances at a time and keeping at least 100% of the app online at
any point of time during the upgrade with 10% of additional capacity.

##### Example

**Request:**


```http
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Length: 562
Content-Type: application/json; charset=utf-8
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0

{
    "cmd": "env && python3 -m http.server $PORT0",
    "constraints": [
        [
            "hostname",
            "UNIQUE"
        ]
    ],
    "container": {
        "docker": {
            "image": "python:3"
        },
        "type": "DOCKER"
    },
    "cpus": 0.25,
    "healthChecks": [
        {
            "gracePeriodSeconds": 3,
            "intervalSeconds": 10,
            "maxConsecutiveFailures": 3,
            "path": "/",
            "portIndex": 0,
            "protocol": "HTTP",
            "timeoutSeconds": 5
        }
    ],
    "id": "my-app",
    "instances": 2,
    "mem": 50,
    "ports": [
        0
    ],
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5,
        "maximumOverCapacity": 0.5
    }
}
```

**Response:**


```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: http://mesos.vm:8080/v2/apps/my-app
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "args": null,
    "backoffFactor": 1.15,
    "backoffSeconds": 1,
    "maxLaunchDelaySeconds": 3600,
    "cmd": "env && python3 -m http.server $PORT0",
    "constraints": [
        [
            "hostname",
            "UNIQUE"
        ]
    ],
    "container": {
        "docker": {
            "image": "python:3"
        },
        "type": "DOCKER",
        "volumes": []
    },
    "cpus": 0.25,
    "dependencies": [],
    "deployments": [
        {
            "id": "f44fd4fc-4330-4600-a68b-99c7bd33014a"
        }
    ],
    "disk": 0.0,
    "env": {},
    "executor": "",
    "healthChecks": [
        {
            "command": null,
            "gracePeriodSeconds": 3,
            "intervalSeconds": 10,
            "maxConsecutiveFailures": 3,
            "path": "/",
            "portIndex": 0,
            "protocol": "HTTP",
            "timeoutSeconds": 5
        }
    ],
    "id": "/my-app",
    "instances": 2,
    "mem": 50.0,
    "portDefinitions": [
        {"port": 0}
    ],
    "requirePorts": false,
    "storeUrls": [],
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5,
        "maximumOverCapacity": 0.5
    },
    "uris": [],
    "user": null,
    "version": "2014-08-18T22:36:41.451Z"
}
```

##### Example (create an app with an already existing ID)
If the ID you are trying to create already exists, then the create operation fails.

**Request:**


```http
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Type: application/json; charset=utf-8
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0

{
    "id":"duplicated",
    "cmd":"sleep 100",
    "cpus":0.1,
    "mem":16,
    "instances":1
}
```

**Response:**


```http
HTTP/1.1 409 Conflict
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "id":"duplicated",
    "message": "An app with id [/duplicated] already exists."
}
```
