---
title: REST API
---

# Marathon REST API

* [Apps](#apps)
  * [POST /v2/apps](#post-/v2/apps): Create and start a new app
  * [GET /v2/apps](#get-/v2/apps): List all running apps
  * [GET /v2/apps?cmd={command}](#get-/v2/apps?cmd={command}): List all running
    apps, filtered by `command`
  * [GET /v2/apps/{appId}](#get-/v2/apps/{appid}): List the app `appId`
  * [GET /v2/apps/{appId}/versions](#get-/v2/apps/{appid}/versions): List the versions of the application with id `appId`.
  * [GET /v2/apps/{appId}/versions/{version}](#get-/v2/apps/{appid}/versions/{version}): List the configuration of the application with id `appId` at version `version`.
  * [PUT /v2/apps/{appId}](#put-/v2/apps/{appid}): Change config of the app
    `appId`
  * [DELETE /v2/apps/{appId}](#delete-/v2/apps/{appid}): Destroy app `appId`
  * [GET /v2/apps/{appId}/tasks](#get-/v2/apps/{appid}/tasks): List running tasks
    for app `appId`
  * [DELETE /v2/apps/{appId}/tasks?host={host}&scale={true|false}](#delete-/v2/apps/{appid}/tasks?host={host}&scale={true|false}):
    kill tasks belonging to app `appId`
  * [DELETE /v2/apps/{appId}/tasks/{taskId}?scale={true|false}](#delete-/v2/apps/{appid}/tasks/{taskid}?scale={true|false}):
    Kill the task `taskId` that belongs to the application `appId`
* [Tasks](#tasks)
  * [GET /v2/tasks](#get-/v2/tasks): List all running tasks
* [Event Subscriptions](#event-subscriptions)
  * [POST /v2/eventSubscriptions?callbackUrl={url}](#post-/v2/eventsubscriptions?callbackurl={url}): Register a callback URL as an event subscriber
  * [GET /v2/eventSubscriptions](#get-/v2/eventsubscriptions): List all event subscriber callback URLs
  * [DELETE /v2/eventSubscriptions?callbackUrl={url}](#delete-/v2/eventsubscriptions?callbackurl={url}) Unregister a callback URL from the event subscribers list

### Apps

#### POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

{% highlight json %}
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
    "dependencies": ["/product/db/mongo", "/product/db", "../../db"],
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5
    },
    "version": "2014-03-01T23:29:30.158Z"
}
{% endhighlight %}

##### id

Unique identifier for the app consisting of a series of names separated by slashes.
Each name must be at least 1 character and may
only contain digits (`0-9`), dashes (`-`), dots (`.`), and lowercase letters
(`a-z`). The name may not begin or end with a dash.

The allowable format is represented by the following regular expression  
`^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])$`

##### args

An array of strings that represents an alternative mode of specifying the command to run. This was motivated by safe usage of containerizer features like a custom Docker ENTRYPOINT. This args field may be used in place of cmd even when using the default command executor. This change mirrors API and semantics changes in the Mesos CommandInfo protobuf message starting with version `0.20.0`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

##### backoffSeconds, backoffFactor

Configures exponential backoff behavior when launching potentially sick apps.
This prevents sandboxes associated with consecutively failing tasks from
filling up the hard disk on Mesos slaves. The backoff period is multiplied by
the factor for each consecutive failure.  This applies also to tasks that are
killed due to failing too many health checks.

##### cmd

The command that is executed.  This value is wrapped by Mesos via `/bin/sh -c ${app.cmd}`.  Either `cmd` or `args` must be supplied. It is invalid to supply both `cmd` and `args` in the same app.

##### constraints

Valid constraint operators are one of ["UNIQUE", "CLUSTER",
"GROUP_BY"]. For additional information on using placement constraints see
the [Constraints wiki page](https://github.com/mesosphere/marathon/wiki/Constraints).

##### container

Additional data passed to the containerizer on application launch.  These consist of a type, zero or more volumes, and additional type-specific options.  Volumes and type are optional (the default type is DOCKER).  In order to make use of the docker containerizer, specify `--containerizers=docker,mesos` to the Mesos slave.

##### dependencies

A list of services upon which this application depends. An order is derived from the dependencies for performing start/stop and upgrade of the application.  For example, an application `/a` relies on the services `/b` which itself relies on `/c`. To start all 3 applications, first `/c` is started than `/b` than `/a`.

##### healthChecks

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

###### HEALTH CHECK OPTIONS

* `gracePeriodSeconds` (Optional. Default: 15): Health check failures are
  ignored within this number of seconds of the task being started or until the
  task becomes healthy for the first time.
* `intervalSeconds` (Optional. Default: 10): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3) : Number of consecutive health
  check failures after which the unhealthy task should be killed.
* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP", "TCP", or "COMMAND".
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

##### ports

An array of required port resources on the host. To generate one or more
arbitrary free ports for each application instance, pass zeros as port
values. Each port value is exposed to the instance via environment variables
`$PORT0`, `$PORT1`, etc. Ports assigned to running instances are also available
via the task resource.

##### upgradeStrategy
During an upgrade all instances of an application get replaced by a new version.
The `minimumHealthCapacity` defines the minimum number of healthy nodes, that do not sacrifice overall application purpose. 
It is a number between `0` and `1` which is multiplied with the instance count. 
The default `minimumHealthCapacity` is `1`, which means no old instance can be stopped, before all new instances are deployed. 
A value of `0.5` means that an upgrade can be deployed side by side, by taking half of the instances down in the first step, 
deploy half of the new version and than take the other half down and deploy the rest. 
A value of `0` means take all instances down immediately and replace with the new application.

##### Example

**Request:**


{% highlight http %}
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
        "minimumHealthCapacity": 0.5
    }
}
{% endhighlight json %}

**Response:**


{% highlight http %}
HTTP/1.1 201 Created
Content-Type: application/json
Location: http://mesos.vm:8080/v2/apps/my-app
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "args": null, 
    "backoffFactor": 1.15, 
    "backoffSeconds": 1, 
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
    "ports": [
        0
    ], 
    "requirePorts": false, 
    "storeUrls": [], 
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5
    }, 
    "uris": [], 
    "user": null, 
    "version": "2014-08-18T22:36:41.451Z"
}
{% endhighlight %}

#### GET `/v2/apps`

List all running applications.

##### Example

**Request:**

{% highlight http %}
GET /v2/apps HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [
        {
            "args": null, 
            "backoffFactor": 1.15, 
            "backoffSeconds": 1, 
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
            "ports": [
                10000
            ], 
            "requirePorts": false, 
            "storeUrls": [], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "upgradeStrategy": {
                "minimumHealthCapacity": 0.5
            }, 
            "uris": [], 
            "user": null, 
            "version": "2014-08-18T22:36:41.451Z"
        }
    ]
}
{% endhighlight %}

#### GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.

##### Example

**Request:**

{% highlight http %}
GET /v2/apps?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [
        {
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "id": "my-app", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                13321, 
                10982
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-04-04T06:25:31.399Z"
        }
    ]
}
{% endhighlight %}

#### GET `/v2/apps/{appId}`

List the application with id `appId`.

##### Example

**Request:**

{% highlight http %}
GET /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "app": {
        "cmd": "env && sleep 60", 
        "constraints": [
            [
                "hostname", 
                "UNIQUE", 
                ""
            ]
        ], 
        "container": null, 
        "cpus": 0.1, 
        "env": {
            "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
        }, 
        "executor": "", 
        "id": "my-app", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            13321, 
            10982
        ], 
        "tasks": [
            {
                "host": "agouti.local", 
                "id": "my-app_0-1396592732285", 
                "ports": [
                    31876, 
                    31877
                ], 
                "stagedAt": "2014-04-04T06:25:32.287Z", 
                "startedAt": "2014-04-04T06:25:32.766Z", 
                "version": "2014-04-04T06:25:31.399Z"
            }
        ], 
        "tasksRunning": 1, 
        "tasksStaged": 0, 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ], 
        "version": "2014-04-04T06:25:31.399Z"
    }
}
{% endhighlight %}

#### GET `/v2/apps/{appId}/versions`

List the versions of the application with id `appId`.

##### Example

**Request:**

{% highlight http %}
GET /v2/apps/my-app/versions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "versions": [
        "2014-04-04T06:25:31.399Z"
    ]
}
{% endhighlight %}

#### GET `/v2/apps/{appId}/versions/{version}`

List the configuration of the application with id `appId` at version `version`.

##### Example

**Request:**

{% highlight http %}
GET /v2/apps/my-app/versions/2014-03-01T23:17:50.295Z HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "cmd": "sleep 60", 
    "constraints": [], 
    "container": null, 
    "cpus": 0.1, 
    "env": {}, 
    "executor": "", 
    "id": "my-app", 
    "instances": 4, 
    "mem": 5.0, 
    "ports": [
        18027, 
        13200
    ], 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ], 
    "version": "2014-03-01T23:17:50.295Z"
}
{% endhighlight %}

#### PUT `/v2/apps/{appId}`

Change parameters of a running application.  The new application parameters
apply only to subsequently created tasks, and currently running tasks are
__not__ pre-emptively restarted.

##### Example

**Request:**

{% highlight http %}
PUT /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 126
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "cmd": "sleep 55", 
    "constraints": [
        [
            "hostname", 
            "UNIQUE", 
            ""
        ]
    ], 
    "cpus": "0.3", 
    "instances": "2", 
    "mem": "9", 
    "ports": [
        9000
    ]
}
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
{% endhighlight %}


##### Example (version rollback)

If the `version` key is supplied in the JSON body, the rest of the object is ignored.  If the supplied version is known, then the app is updated (a new version is created) with those parameters.  Otherwise, if the supplied version is not known Marathon responds with a 404.

**Request:**

{% highlight http %}
PUT /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 39
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "version": "2014-03-01T23:17:50.295Z"
}
{% endhighlight %}

{% highlight http %}
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
{% endhighlight %}

#### DELETE `/v2/apps/{appId}`

Destroy an application. All data about that application will be deleted.

##### Example

**Request:**

{% highlight http %}
DELETE /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}


**Response:**

{% highlight http %}
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
{% endhighlight %}


#### GET `/v2/apps/{appId}/tasks`

List all running tasks for application `appId`.

##### Example (as JSON)

**Request:**

{% highlight http %}
GET /v2/apps/my-app/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "host": "agouti.local", 
            "id": "my-app_1-1396592790353", 
            "ports": [
                31336, 
                31337
            ], 
            "stagedAt": "2014-04-04T06:26:30.355Z", 
            "startedAt": "2014-04-04T06:26:30.860Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }, 
        {
            "host": "agouti.local", 
            "id": "my-app_0-1396592784349", 
            "ports": [
                31382, 
                31383
            ], 
            "stagedAt": "2014-04-04T06:26:24.351Z", 
            "startedAt": "2014-04-04T06:26:24.919Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }
    ]
}
{% endhighlight %}

##### Example (as text)

**Request:**

{% highlight http %}
GET /v2/apps/my-app/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

my-app  19385 agouti.local:31336  agouti.local:31364  agouti.local:31382  
my-app  11186 agouti.local:31337  agouti.local:31365  agouti.local:31383  
{% endhighlight %}

#### DELETE `/v2/apps/{appId}/tasks?host={host}&scale={true|false}`

Kill tasks that belong to the application `appId`, optionally filtered by the
task's `host`.

The query parameters `host` and `scale` are both optional.  If `host` is
specified, only tasks running on the supplied slave are killed.  If
`scale=true` is specified, then the application is scaled down by the number of
killed tasks.  The `scale` parameter defaults to `false`.

##### Example

**Request:**

{% highlight http %}
DELETE /v2/apps/my-app/tasks?host=mesos.vm&scale=false HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": []
}
{% endhighlight %}

#### DELETE `/v2/apps/{appId}/tasks/{taskId}?scale={true|false}`

Kill the task with ID `taskId` that belongs to the application `appId`.

The query parameter `scale` is optional.  If `scale=true` is specified, then
the application is scaled down one if the supplied `taskId` exists.  The
`scale` parameter defaults to `false`.

##### Example

**Request:**

{% highlight http %}
DELETE /v2/apps/my-app/tasks/my-app_3-1389916890411 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "task": {
        "host": "mesos.vm",
        "id": "my-app_3-1389916890411",
        "ports": [
            31509,
            31510
        ],
        "stagedAt": "2014-01-17T00:01+0000",
        "startedAt": "2014-01-17T00:01+0000"
    }
}
{% endhighlight %}

### Tasks

#### GET `/v2/tasks`

List tasks of all running applications.

##### Example (as JSON)

**Request:**

{% highlight http %}
GET /v2/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "appId": "my-app", 
            "host": "agouti.local", 
            "id": "my-app_2-1396592796360", 
            "ports": [
                31364, 
                31365
            ], 
            "stagedAt": "2014-04-04T06:26:36.362Z", 
            "startedAt": "2014-04-04T06:26:37.285Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }, 
        {
            "appId": "my-app", 
            "host": "agouti.local", 
            "id": "my-app_1-1396592790353", 
            "ports": [
                31336, 
                31337
            ], 
            "stagedAt": "2014-04-04T06:26:30.355Z", 
            "startedAt": "2014-04-04T06:26:30.860Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }, 
        {
            "appId": "my-app", 
            "host": "agouti.local", 
            "id": "my-app_0-1396592784349", 
            "ports": [
                31382, 
                31383
            ], 
            "stagedAt": "2014-04-04T06:26:24.351Z", 
            "startedAt": "2014-04-04T06:26:24.919Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }
    ]
}
{% endhighlight %}

##### Example (as text)

**Request:**

{% highlight http %}
GET /v2/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

my-app  19385 agouti.local:31336  agouti.local:31364  agouti.local:31382  
my-app  11186 agouti.local:31337  agouti.local:31365  agouti.local:31383  
{% endhighlight %}

### Event Subscriptions

#### POST /v2/eventSubscriptions?callbackUrl={url}

Register a callback URL as an event subscriber.

NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`.

**Request:**

{% highlight http %}
POST /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}


**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrl": "http://localhost:9292/callback", 
    "clientIp": "0:0:0:0:0:0:0:1", 
    "eventType": "subscribe_event"
}
{% endhighlight %}

#### GET `/v2/eventSubscriptions`

List all event subscriber callback URLs.

NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`.

##### Example

**Request:**

{% highlight http %}
GET /v2/eventSubscriptions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}

**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrls": [
        "http://localhost:9292/callback"
    ]
}
{% endhighlight %}

#### DELETE `/v2/eventSubscriptions?callbackUrl={url}`

Unregister a callback URL from the event subscribers list.

NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`.

##### Example

**Request:**

{% highlight http %}
DELETE /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{% endhighlight %}


**Response:**

{% highlight http %}
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrl": "http://localhost:9292/callback", 
    "clientIp": "0:0:0:0:0:0:0:1", 
    "eventType": "unsubscribe_event"
}
{% endhighlight %}
