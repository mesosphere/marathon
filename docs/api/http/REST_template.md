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
    "cmd": "env && sleep 300",
    "constraints": [
        ["attribute", "OPERATOR", "value"]
    ],
    "container": {
        "image": "docker:///dockeruser/oracle-java7",
        "options": ["-v", "/etc/config:/etc/config:ro"]
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
"GROUP_BY"]. For additional information on using placement constraints see
the [Constraints wiki page](https://github.com/mesosphere/marathon/wiki/Constraints).

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

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v2/apps id=myApp cmd='env && sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' env:='{"LD_LIBRARY_PATH": "/usr/local/lib/myLib"}' constraints:='[["hostname", "UNIQUE", ""]]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

**Response:**

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v2/apps id=myApp cmd='env && sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' env:='{"LD_LIBRARY_PATH": "/usr/local/lib/myLib"}' constraints:='[["hostname", "UNIQUE", ""]]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

#### GET `/v2/apps`

List all running applications.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps

#### GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps?cmd=sleep%2060

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps?cmd=sleep%2060

#### GET `/v2/apps/{appId}`

List the application with id `appId`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp

#### GET `/v2/apps/{appId}/versions`

List the versions of the application with id `appId`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp/versions

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp/versions

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

http --print=HB --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 55' constraints:='[["hostname", "UNIQUE", ""]]' ports:='[9000]' cpus=0.3 mem=9 instances=2

**Response:**

http --print=hb --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 55' constraints:='[["hostname", "UNIQUE", ""]]' ports:='[9000]' cpus=0.3 mem=9 instances=2

:http --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 60' constraints:='[]' ports:='[0, 0]' cpus=0.1 mem=5 instances=3

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

http --print=HB --ignore-stdin --json --pretty format DELETE localhost:8080/v2/apps/myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

**Response:**

http --print=hb --ignore-stdin --json --pretty format DELETE localhost:8080/v2/apps/myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

#### GET `/v2/apps/{appId}/tasks`

List all running tasks for application `appId`.

##### Example (as JSON)

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp/tasks

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp/tasks

##### Example (as text)

**Request:**

http --print=HB --ignore-stdin --pretty format GET localhost:8080/v2/apps/myApp/tasks Accept:text/plain

**Response:**

http --print=hb --ignore-stdin --pretty format GET localhost:8080/v2/apps/myApp/tasks Accept:text/plain

#### DELETE `/v2/apps/{appId}/tasks?host={host}&scale={true|false}`

Kill tasks that belong to the application `appId`, optionally filtered by the
task's `host`.

The query parameters `host` and `scale` are both optional.  If `host` is
specified, only tasks running on the supplied slave are killed.  If
`scale=true` is specified, then the application is scaled down by the number of
killed tasks.  The `scale` parameter defaults to `false`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format DELETE 'localhost:8080/v2/apps/myApp/tasks?host=mesos.vm&scale=false'

**Response:**

http --print=hb --ignore-stdin --json --pretty format DELETE 'localhost:8080/v2/apps/myApp/tasks?host=mesos.vm&scale=false'

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

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/tasks

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/tasks

##### Example (as text)

**Request:**

http --print=HB --ignore-stdin --pretty format GET localhost:8080/v2/tasks Accept:text/plain

**Response:**

http --print=hb --ignore-stdin --pretty format GET localhost:8080/v2/tasks Accept:text/plain

### _Event Subscriptions_

#### POST /v2/eventSubscriptions?callbackUrl={url}

Register a callback URL as an event subscriber.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

**Request:**

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback

:http --ignore-stdin DELETE localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback

**Response:**

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback

#### GET `/v2/eventSubscriptions`

List all event subscriber callback URLs.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/eventSubscriptions

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/eventSubscriptions

#### DELETE `/v2/eventSubscriptions?callbackUrl={url}`

Unregister a callback URL from the event subscribers list.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format DELETE localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback

:http --ignore-stdin --json POST localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback

**Response:**

http --print=hb --ignore-stdin --json --pretty format DELETE localhost:8080/v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback


## API Version 1 _(DEPRECATED)_

### _Apps_

#### POST `/v1/apps/start`

##### Example

**Request:**

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/start id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

**Response:**

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/start id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

#### GET `/v1/apps`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/apps

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/apps

#### POST `/v1/apps/stop`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/stop id=myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

**Request:**

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/stop id=myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

#### POST `/v1/apps/scale`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/scale id=myApp instances=4

**Response:**

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v1/apps/scale id=myApp instances=4

:http --ignore-stdin PUT localhost:8080/v2/apps/myApp instances=3

#### GET `/v1/apps/search?id={appId}&cmd={command}`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/search?id=myApp

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/search?id=myApp

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/search?cmd=sleep%2060

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/search?cmd=sleep%2060

#### GET `/v1/apps/{appId}/tasks`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/myApp/tasks

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/apps/myApp/tasks

### _Endpoints_

#### GET `/v1/endpoints`

##### Example (as JSON)

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/endpoints

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/endpoints

##### Example (as text)

**Request:**

http --print=HB --ignore-stdin --pretty format GET localhost:8080/v1/endpoints Accept:text/plain

**Response:**

http --print=hb --ignore-stdin --pretty format GET localhost:8080/v1/endpoints Accept:text/plain

#### GET `/v1/endpoints/{appId}`

##### Example (as JSON)

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/endpoints/myApp

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/endpoints/myApp

##### Example (as text)

**Request:**

http --print=HB --ignore-stdin --pretty format GET localhost:8080/v1/endpoints/myApp Accept:text/plain

**Response:**

http --print=hb --ignore-stdin --pretty format GET localhost:8080/v1/endpoints/myApp Accept:text/plain

### Tasks

#### GET `/v1/tasks`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/tasks

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/tasks


#### POST `/v1/tasks/kill?appId={appId}&host={host}&id={taskId}&scale={true|false}`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format POST localhost:8080/v1/tasks/kill?appId=myApp

**Response:**

http --print=hb --ignore-stdin --json --pretty format POST localhost:8080/v1/tasks/kill?appId=myApp

### _Debug_

#### GET `/v1/debug/isLeader`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/debug/isLeader

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/debug/isLeader

#### GET `/v1/debug/leaderUrl`

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v1/debug/leaderUrl

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v1/debug/leaderUrl
