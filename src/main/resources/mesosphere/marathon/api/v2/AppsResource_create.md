## POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

```
{
    "cmd": "(env && sleep 300)",
    "constraints": [
        ["attribute", "OPERATOR", "value"]
    ],
    "container": {
        "image": "docker:///zaiste/postgresql",
        "options": ["-e", "X=7"]
    },
    "cpus": 2,
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    },
    "executor": "",
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
    "version": "2014-03-01T23:29:30.158Z"
}
```

##### `constraints`

Valid constraint operators are one of ["UNIQUE", "CLUSTER",
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
wiki page.

A health check is considered passing if (1) its HTTP response code is between
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
* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".
  _Note: only used if `protocol == "HTTP"`._
* `portIndex` (Optional. Default: 0): Index in this app's `ports` array to be
  used for health requests. An index is used so the app can use random ports,
  like "[0, 0, 0]" for example, and tasks could be started with port environment
  variables like `$PORT1`.
* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP" or "TCP".
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

##### `id`

Unique string identifier for the app. It must be at least 1 character and may
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

Configures exponential backoff behavior when launching potentially sick apps.
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
    "id": "myApp", 
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
