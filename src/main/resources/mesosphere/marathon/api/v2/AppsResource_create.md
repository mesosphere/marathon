## POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

```
{
    "id": "/product/service/myApp",
    "cmd": "env && sleep 300",
    "args": ["/bin/sh", "-c", "env && sleep 300"]
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
    },
    "cpus": 1.5,
    "mem": 256.0,
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    },
    "executor": "",
    "constraints": [
        ["attribute", "OPERATOR", "value"]
    ],
    "healthChecks": [
        {
            "protocol": "HTTP",
            "path": "/health",
            "gracePeriodSeconds": 3,
            "intervalSeconds": 10,
            "portIndex": 0,
            "timeoutSeconds": 10
        },
        {
            "protocol": "TCP",
            "gracePeriodSeconds": 3,
            "intervalSeconds": 5,
            "portIndex": 1,
            "timeoutSeconds": 5
        },
        {
            "protocol": "COMMAND",
            "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" }
        }
    ],
    "id": "my-app",
    "instances": 3,
    "mem": 256.0,
    "ports": [
        8080,
        9000
    ],
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "tasksRunning": 3, 
    "tasksStaged": 0, 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ],
    "healthChecks": [{
        "protocol": "http",
        "path": "/",
        "portIndex": 0,
        "gracePeriodSeconds": 15,
        "intervalSeconds": 10,
        "timeoutSeconds": 20,
        "maxConsecutiveFailures": 3
    },{
        "protocol": "tcp",
        "portIndex": 0,
        "gracePeriodSeconds": 15,
        "intervalSeconds": 10,
        "timeoutSeconds": 20,
        "maxConsecutiveFailures": 3
    }],
    "dependencies": ["/product/db/mongo", "/product/db", "../../db"],
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5
    },
    "version": "2014-03-01T23:29:30.158Z"
}
```


_id:_ [String] The identifier of the application. This identifier is a path. If the identifier does not start with a slash, 
than it is interpreted as relative path and will be translated into an absolute path.

_cmd:_ [String] The command that is executed.  This value is wrapped by Mesos via `/bin/sh -c ${app.cmd}`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

_args:_ [Array[String]] An alternative mode of specifying the command to run. This was motivated by safe usage of containerizer features like a custom Docker ENTRYPOINT. This args field may be used in place of cmd even when using the default command executor. This change mirrors API and semantics changes in the Mesos CommandInfo protobuf message starting with version `0.20.0`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

_container:_ [Container] Additional data passed to the containerizer on application launch.  These consist of a type, zero or more volumes, and additional type-specific options.  Volumes and type are optional (the default type is DOCKER).  In order to make use of the docker containerizer, specify `--containerizers=docker,mesos` to the Mesos slave.

_cpus:_: [Float] the number of CPU`s this application needs per instance. This number does not have to be integer, but can be a fraction.

_mem:_: [Float] the amount of memory in MB that is needed for the application per instance. 

_env:_: [Environent] key/value pairs that get added to the environment variables of the process to start.

_executor:_ [String] the executor to use to launch this application. Different executors are available. The simplest one (and the default if none is given) is //cmd, which takes the cmd and executes that on the shell level.

_constraints:_ [Array[String]] Valid constraint operators are one of ["UNIQUE", "LIKE", "UNLIKE", "CLUSTER", "GROUP_BY"].  For additional information on using placement constraints see [Marathon, a Mesos framework, adds Placement Constraints](http://mesosphere.com/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints).

_instances:_: The number of instances of this application to start. Please note: this number can be changed everytime as needed to scale the application.

_ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeros as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the task resource.

_taskRunning:_ [Integer] the number of tasks running for this application definition. This parameter can not be set.

_tasksStaged:_ [Integer] the number of tasks staged to run. This parameter can not be set.

_uris:_ [Array[String]] uris defined here are resolved, before the application gets started. If the application has external dependencies, they should be defined here.

_healthChecks:_ [[Array[HealthCheck]] Methods of probing a task to determine its health. An application is considered healthy, if all instances are running and all health checks pass. To use this feature, tasks need either an http endpoint or a tcp socket to listen to.

_dependencies:_ [Array[String]] an application can have dependencies to other applications. An order is derived from the dependencies for performing start/stop and upgrade of the application.
For example, an application /a relies on the services /b which itself relies on /c. To start all 3 applications, first /c is started than /b than /a.

_upgradeStrategy:_ [UpgradeStrategy] during an upgrade all instances of an application get replaced by a new version. 
The minimumHealthCapacity defines the minimum number of healthy nodes, that do not sacrifice overall application purpose. 
It is a number between 0 and 1 which is multiplied with the instance count. 
The default minimumHealthCapacity is 1, which means no old instance can be stopped, before all new instances are deployed. 
A value of 0.5 means that an upgrade can be deployed side by side, by taking half of the instances down in the first step, 
deploy half of the new version and than take the other half down and deploy the rest. 
A value of 0 means take all instances down immediately and replace with the new application.

_version:_ [String] ISODate string, which shows the last change of the application. This parameter can not be set.

##### `constraints`

Valid constraint operators are one of ["UNIQUE", "LIKE", "UNLIKE", "CLUSTER",
"GROUP_BY"]. For additional information on using placement constraints see
the [Constraints wiki page](https://github.com/mesosphere/marathon/wiki/Constraints).

##### `container`

Additional data passed to the container on application launch. These consist of
an "image" and an array of string options. The meaning of this data is fully
dependent upon the executor. Furthermore, _it is invalid to pass container
options when using the default command executor_.

##### `healthChecks`

An array of checks to be performed on running tasks to determine if they are
operating as expected. Health checks begin immediately upon task launch. For
design details, refer to the [health checks](https://github.com/mesosphere/marathon/wiki/Health-Checks)
wiki page.  By default, health checks are executed by the Marathon scheduler.
It's possible with Mesos `0.20.0` and higher to execute health checks on the hosts where
the tasks are running by supplying the `--executor_health_checks` flag to Marathon.
In this case, the only supported protocol is `COMMAND` and each app is limited to
at most one defined health check.

An HTTP health check is considered passing if (1) its HTTP response code is between
200 and 399, inclusive, and (2) its response is received within the
`timeoutSeconds` period. If a task fails more than `maxConseutiveFailures`
health checks consecutively, that task is killed.

###### Health Check Options

* `gracePeriodSeconds` (Optional. Default: 15): Health check failures are
  ignored within this number of seconds of the task being started or until the
  task becomes healthy for the first time.
* `intervalSeconds` (Optional. Default: 10): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3) : Number of consecutive health
  check failures after which the unhealthy task should be killed.
* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP", "TCP", or "Command".
* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".
  _Note: only used if `protocol == "HTTP"`._
* `command`: Command to run in order to determine the health of a task.
  _Note: only used if `protocol == "COMMAND"`, and only available if Marathon is
  started with the `--executor_health_checks` flag.
* `portIndex` (Optional. Default: 0): Index in this app's `ports` array to be
  used for health requests. An index is used so the app can use random ports,
  like "[0, 0, 0]" for example, and tasks could be started with port environment
  variables like `$PORT1`.
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

##### `id`

Unique identifier for the app consisting of a series of names separated by slashes.
Each name must be at least 1 character and may
only contain digits (`0-9`), dashes (`-`), dots (`.`), and lowercase letters
(`a-z`). The name may not begin or end with a dash.

(The allowable format is represented by the regular expression
`^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])$`.)

##### `ports`

An array of required port resources on the host. To generate one or more
arbitrary free ports for each application instance, pass zeros as port
values. Each port value is exposed to the instance via environment variables
`$PORT0`, `$PORT1`, etc. Ports assigned to running instances are also available
via the task resource.

##### `backoffSeconds` and `backoffFactor`

Configure exponential backoff behavior when launching potentially sick apps.
This prevents sandboxes associated with consecutively failing tasks from
filling up the hard disk on Mesos slaves. The backoff period is multiplied by
the factor for each consecutive failure.  This applies also to tasks that are
killed due to failing too many health checks.

### Example

**Request:**


```
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 273
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "id": "myapp", 
    "cmd": "env && sleep 60", 
    "constraints": [
        [
            "hostname", 
            "UNIQUE", 
            ""
        ]
    ], 
    "cpus": "0.1", 
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    }, 
    "instances": "3", 
    "mem": "5", 
    "ports": [
        0, 
        0
    ], 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ]
}
```

**Response:**


```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
