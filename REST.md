<!-- AUTO-GENERATED FILE; DO NOT MODIFY -->
# RESTful Marathon

## API Version 2

* [Apps](#apps)
  * [POST /v2/apps](#post-v2apps): Create and start a new app
  * [GET /v2/apps](#get-v2apps): List all running apps
  * [GET /v2/apps?cmd={command}](#get-v2appscmdcommand): List all running
    apps, filtered by `command`
  * [GET /v2/apps/{appId}](#get-v2appsappid): List the app `appId`
  * [GET /v2/apps/{appId}/versions](#get-v2appsappidversions): List the versions of the application with id `appId`.
  * [GET /v2/apps/{appId}/versions/{version}](#get-v2appsappidversionsversion): List the configuration of the application with id `appId` at version `version`.
  * [PUT /v2/apps/{appId}](#put-v2appsappid): Change config of the app
    `appId`
  * [DELETE /v2/apps/{appId}](#delete-v2appsappid): Destroy app `appId`
  * [GET /v2/apps/{appId}/tasks](#get-v2appsappidtasks): List running tasks
    for app `appId`
  * [DELETE /v2/apps/{appId}/tasks?host={host}&scale={true|false}](#delete-v2appsappidtaskshosthostscaletruefalse):
    kill tasks belonging to app `appId`
  * [DELETE /v2/apps/{appId}/tasks/{taskId}?scale={true|false}](#delete-v2appsappidtaskstaskidscaletruefalse):
    Kill the task `taskId` that belongs to the application `appId`
* [Tasks](#tasks)
  * [GET /v2/tasks](#get-v2tasks): List all running tasks
* [Event Subscriptions](#event-subscriptions)
  * [POST /v2/eventSubscriptions?callbackUrl={url}](#post-v2eventsubscriptionscallbackurlurl): Register a callback URL as an event subscriber
  * [GET /v2/eventSubscriptions](#get-v2eventsubscriptions): List all event subscriber callback URLs
  * [DELETE /v2/eventSubscriptions?callbackUrl={url}](#delete-v2eventsubscriptionscallbackurlurl) Unregister a callback URL from the event subscribers list

### _Apps_

#### POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

```json
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
            "acceptableResponses": [200],
            "path": "/health",
            "initialDelaySeconds": 3,
            "intervalSeconds": 10,
            "portIndex": 0,
            "timeoutSeconds": 10
        },
        {
            "protocol": "TCP",
            "initialDelaySeconds": 3,
            "intervalSeconds": 5,
            "portIndex": 1,
            "timeoutSeconds": 5
        }
    ],
    "id": "myApp",
    "instances": 3,
    "mem": 256.0,
    "ports": [
        8080,
        9000
    ],
    "taskRateLimit": 1.0,
    "tasksRunning": 3, 
    "tasksStaged": 0, 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ], 
    "version": "2014-03-01T23:29:30.158Z"
}
```

_Constraints:_ Valid constraint operators are one of ["UNIQUE", "CLUSTER",
"GROUP_BY"].  For additional information on using placement constraints see
[Marathon, a Mesos framework, adds Placement Constraints](http://mesosphere.io/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints).

_Container:_ Additional data passed to the container on application launch.
These consist of an "image" and an array of string options.  The meaning of
this data is fully dependent upon the executor.  Furthermore, _it is invalid to
pass container options when using the default command executor_.

_Ports:_ An array of required port resources on the host.  To generate one or
more arbitrary free ports for each application instance, pass zeros as port
values.  Each port value is exposed to the instance via environment variables
`$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also
available via the task resource.

##### Example

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
HTTP/1.1 201 Created
Content-Length: 0
Content-Type: application/json
Location: http://localhost:8080/v2/apps/myApp
Server: Jetty(8.y.z-SNAPSHOT)


```

#### GET `/v2/apps`

List all running applications.

##### Example

**Request:**

```
GET /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
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
            "id": "myApp", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                13321, 
                10982
            ], 
            "taskRateLimit": 1.0, 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-04-04T06:25:31.399Z"
        }
    ]
}
```

#### GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.

##### Example

**Request:**

```
GET /v2/apps?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
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
            "id": "myApp", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                13321, 
                10982
            ], 
            "taskRateLimit": 1.0, 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-04-04T06:25:31.399Z"
        }
    ]
}
```

#### GET `/v2/apps/{appId}`

List the application with id `appId`.

##### Example

**Request:**

```
GET /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
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
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            13321, 
            10982
        ], 
        "taskRateLimit": 1.0, 
        "tasks": [
            {
                "host": "agouti.local", 
                "id": "myApp_0-1396592732285", 
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
```

#### GET `/v2/apps/{appId}/versions`

List the versions of the application with id `appId`.

##### Example

**Request:**

```
GET /v2/apps/myApp/versions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "versions": [
        "2014-04-04T06:25:31.399Z"
    ]
}
```

#### GET `/v2/apps/{appId}/versions/{version}`

List the configuration of the application with id `appId` at version `version`.

##### Example

**Request:**

```
GET /v2/apps/myApp/versions/2014-03-01T23:17:50.295Z HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
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
    "id": "myApp", 
    "instances": 4, 
    "mem": 5.0, 
    "ports": [
        18027, 
        13200
    ], 
    "taskRateLimit": 1.0, 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ], 
    "version": "2014-03-01T23:17:50.295Z"
}
```

#### PUT `/v2/apps/{appId}`

Change parameters of a running application.  The new application parameters
apply only to subsequently created tasks, and currently running tasks are
__not__ pre-emptively restarted.

##### Example

**Request:**

```
PUT /v2/apps/myApp HTTP/1.1
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
```

**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


##### Example (version rollback)

If the `version` key is supplied in the JSON body, the rest of the object is ignored.  If the supplied version is known, then the app is updated (a new version is created) with those parameters.  Otherwise, if the supplied version is not known Marathon responds with a 404.

**Request:**

```
PUT /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 39
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "version": "2014-03-01T23:17:50.295Z"
}
```

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
```

#### DELETE `/v2/apps/{appId}`

Destroy an application. All data about that application will be deleted.

##### Example

**Request:**

```
DELETE /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```


**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### GET `/v2/apps/{appId}/tasks`

List all running tasks for application `appId`.

##### Example (as JSON)

**Request:**

```
GET /v2/apps/myApp/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "host": "agouti.local", 
            "id": "myApp_1-1396592790353", 
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
            "id": "myApp_0-1396592784349", 
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
```

##### Example (as text)

**Request:**

```
GET /v2/apps/myApp/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

myApp	19385	agouti.local:31336	agouti.local:31364	agouti.local:31382	
myApp	11186	agouti.local:31337	agouti.local:31365	agouti.local:31383	

```

#### DELETE `/v2/apps/{appId}/tasks?host={host}&scale={true|false}`

Kill tasks that belong to the application `appId`, optionally filtered by the
task's `host`.

The query parameters `host` and `scale` are both optional.  If `host` is
specified, only tasks running on the supplied slave are killed.  If
`scale=true` is specified, then the application is scaled down by the number of
killed tasks.  The `scale` parameter defaults to `false`.

##### Example

**Request:**

```
DELETE /v2/apps/myApp/tasks?host=mesos.vm&scale=false HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": []
}
```

#### DELETE `/v2/apps/{appId}/tasks/{taskId}?scale={true|false}`

Kill the task with ID `taskId` that belongs to the application `appId`.

The query parameter `scale` is optional.  If `scale=true` is specified, then
the application is scaled down one if the supplied `taskId` exists.  The
`scale` parameter defaults to `false`.

##### Example

**Request:**

```
DELETE /v2/apps/myApp/tasks/myApp_3-1389916890411 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "task": {
        "host": "mesos.vm",
        "id": "myApp_3-1389916890411",
        "ports": [
            31509,
            31510
        ],
        "stagedAt": "2014-01-17T00:01+0000",
        "startedAt": "2014-01-17T00:01+0000"
    }
}
```

### _Tasks_

#### GET `/v2/tasks`

List tasks of all running applications.

##### Example (as JSON)

**Request:**

```
GET /v2/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "appId": "myApp", 
            "host": "agouti.local", 
            "id": "myApp_2-1396592796360", 
            "ports": [
                31364, 
                31365
            ], 
            "stagedAt": "2014-04-04T06:26:36.362Z", 
            "startedAt": "2014-04-04T06:26:37.285Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }, 
        {
            "appId": "myApp", 
            "host": "agouti.local", 
            "id": "myApp_1-1396592790353", 
            "ports": [
                31336, 
                31337
            ], 
            "stagedAt": "2014-04-04T06:26:30.355Z", 
            "startedAt": "2014-04-04T06:26:30.860Z", 
            "version": "2014-04-04T06:26:23.051Z"
        }, 
        {
            "appId": "myApp", 
            "host": "agouti.local", 
            "id": "myApp_0-1396592784349", 
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
```

##### Example (as text)

**Request:**

```
GET /v2/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

myApp	19385	agouti.local:31336	agouti.local:31364	agouti.local:31382	
myApp	11186	agouti.local:31337	agouti.local:31365	agouti.local:31383	

```

### _Event Subscriptions_

#### POST /v2/eventSubscriptions?callbackUrl={url}

Register a callback URL as an event subscriber.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

**Request:**

```
POST /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```


**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrl": "http://localhost:9292/callback", 
    "clientIp": "0:0:0:0:0:0:0:1", 
    "eventType": "subscribe_event"
}
```

#### GET `/v2/eventSubscriptions`

List all event subscriber callback URLs.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

##### Example

**Request:**

```
GET /v2/eventSubscriptions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrls": [
        "http://localhost:9292/callback"
    ]
}
```

#### DELETE `/v2/eventSubscriptions?callbackUrl={url}`

Unregister a callback URL from the event subscribers list.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

##### Example

**Request:**

```
DELETE /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```


**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrl": "http://localhost:9292/callback", 
    "clientIp": "0:0:0:0:0:0:0:1", 
    "eventType": "unsubscribe_event"
}
```


## API Version 1 _(DEPRECATED)_

### _Apps_

#### POST `/v1/apps/start`

##### Example

**Request:**


```
POST /v1/apps/start HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 169
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "cmd": "sleep 60", 
    "cpus": "0.1", 
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


```

#### GET `/v1/apps`

##### Example

**Request:**

```
GET /v1/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

[
    {
        "cmd": "sleep 60", 
        "constraints": [], 
        "container": null, 
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            13024, 
            16512
        ], 
        "taskRateLimit": 1.0, 
        "tasksRunning": 2, 
        "tasksStaged": 0, 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ], 
        "version": "2014-04-04T06:27:40.439Z"
    }
]
```

#### POST `/v1/apps/stop`

##### Example

**Request:**

```
POST /v1/apps/stop HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 15
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "id": "myApp"
}
```


**Request:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### POST `/v1/apps/scale`

##### Example

**Request:**

```
POST /v1/apps/scale HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 33
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "id": "myApp", 
    "instances": "4"
}
```

**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### GET `/v1/apps/search?id={appId}&cmd={command}`

##### Example

**Request:**

```
GET /v1/apps/search?id=myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

[
    {
        "cmd": "sleep 60", 
        "constraints": [], 
        "container": null, 
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            17753, 
            18445
        ], 
        "taskRateLimit": 1.0, 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ], 
        "version": "2014-04-04T06:28:07.054Z"
    }
]
```

##### Example

**Request:**

```
GET /v1/apps/search?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

[
    {
        "cmd": "sleep 60", 
        "constraints": [], 
        "container": null, 
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            17753, 
            18445
        ], 
        "taskRateLimit": 1.0, 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ], 
        "version": "2014-04-04T06:28:07.054Z"
    }
]
```

#### GET `/v1/apps/{appId}/tasks`

##### Example

**Request:**

```
GET /v1/apps/myApp/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "myApp": [
        {
            "host": "agouti.local", 
            "id": "myApp_0-1396592878455", 
            "ports": [
                31759, 
                31760
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_2-1396592890464", 
            "ports": [
                31991, 
                31992
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_1-1396592884460", 
            "ports": [
                31945, 
                31946
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_3-1396592896470", 
            "ports": [
                31938, 
                31939
            ]
        }
    ]
}
```

### _Endpoints_

#### GET `/v1/endpoints`

##### Example (as JSON)

**Request:**

```
GET /v1/endpoints HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

[
    {
        "id": "myApp", 
        "instances": [
            {
                "host": "agouti.local", 
                "id": "myApp_0-1396592878455", 
                "ports": [
                    31759, 
                    31760
                ]
            }, 
            {
                "host": "agouti.local", 
                "id": "myApp_2-1396592890464", 
                "ports": [
                    31991, 
                    31992
                ]
            }, 
            {
                "host": "agouti.local", 
                "id": "myApp_3-1396592896470", 
                "ports": [
                    31938, 
                    31939
                ]
            }
        ], 
        "ports": [
            17753, 
            18445
        ]
    }
]
```

##### Example (as text)

**Request:**

```
GET /v1/endpoints HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

myApp_17753 17753 agouti.local:31991 agouti.local:31938 agouti.local:31759 
myApp_18445 18445 agouti.local:31992 agouti.local:31939 agouti.local:31760 

```

#### GET `/v1/endpoints/{appId}`

##### Example (as JSON)

**Request:**

```
GET /v1/endpoints/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "id": "myApp", 
    "instances": [
        {
            "host": "agouti.local", 
            "id": "myApp_0-1396592878455", 
            "ports": [
                31759, 
                31760
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_2-1396592890464", 
            "ports": [
                31991, 
                31992
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_3-1396592896470", 
            "ports": [
                31938, 
                31939
            ]
        }
    ], 
    "ports": [
        17753, 
        18445
    ]
}
```

##### Example (as text)

**Request:**

```
GET /v1/endpoints/myApp HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

myApp_17753 17753 agouti.local:31991 agouti.local:31938 agouti.local:31988 
myApp_18445 18445 agouti.local:31992 agouti.local:31939 agouti.local:31989 

```

### Tasks

#### GET `/v1/tasks`

##### Example

**Request:**

```
GET /v1/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "myApp": [
        {
            "host": "agouti.local", 
            "id": "myApp_2-1396592951529", 
            "ports": [
                31994, 
                31995
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_3-1396592896470", 
            "ports": [
                31938, 
                31939
            ]
        }, 
        {
            "host": "agouti.local", 
            "id": "myApp_2-1396592939516", 
            "ports": [
                31988, 
                31989
            ]
        }
    ]
}
```


#### POST `/v1/tasks/kill?appId={appId}&host={host}&id={taskId}&scale={true|false}`

##### Example

**Request:**

```
POST /v1/tasks/kill?appId=myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 404 Not Found
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "message": "No tasks matched your filters"
}
```

### _Debug_

#### GET `/v1/debug/isLeader`

##### Example

**Request:**

```
GET /v1/debug/isLeader HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

true
```

#### GET `/v1/debug/leaderUrl`

##### Example

**Request:**

```
GET /v1/debug/leaderUrl HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

agouti.local:8080
```
