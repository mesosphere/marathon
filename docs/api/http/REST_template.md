# RESTful Marathon

## API Version 2

* [Apps](#apps)
  * [POST /v2/apps](#post-v2apps) - Create and start a new app
  * [GET /v2/apps](#get-v2apps) - List all running apps
  * [GET /v2/apps/{app_id}](#get-v2appsapp_id) - List the app `app_id`
  * [GET /v2/apps?cmd={command}](#get-v2appscmdcommand) - List all running apps, filtered by `command`
  * [PUT /v2/apps/{app_id}](#put-v2appsapp_id) - Change config of the app `app_id`
  * [DELETE /v2/apps/{app_id}](#delete-v2appsapp_id) - Destroy app `app_id`
  * [GET /v2/apps/{app_id}/tasks](#get-v2appsapp_idtasks) - List running tasks for app `app_id`
  * [DELETE /v2/apps/{app_id}/tasks?host={host}&scale={true|false}](#delete-v2appsapp_idtaskshosthostscaletruefalse) - kill tasks belonging to app `app_id`
  * [DELETE /v2/apps/{app_id}/tasks/{task_id}?scale={true|false}](#delete-v2appsapp_idtaskstask_idscaletruefalse) - Kill the task `task_id` that belongs to the application `app_id`
* [Tasks](#tasks)
  * [GET /v2/tasks](#get-v2tasks) - List all running tasks
 
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
    "id": "myApp", 
    "instances": 3, 
    "mem": 256.0, 
    "ports": [
        8080, 
        9000
    ], 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ]
}
```

_Constraints:_ Valid constraint operators are one of ["UNIQUE", "CLUSTER", "GROUP_BY"].  For additional information on using placement constraints see [Marathon, a Mesos framework, adds Placement Constraints](http://mesosphere.io/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints).

_Container:_ Additional data passed to the container on application launch.  These consist of an "image" and an array of string options.  The meaning of this data is fully dependent upon the executor.  Furthermore, _it is invalid to pass container options when using the default command executor_.

_Ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeros as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the task resource.

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

#### GET `/v2/apps/{app_id}`

List the application with id `app_id`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps/myApp

#### GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format GET localhost:8080/v2/apps?cmd=sleep%2060

**Response:**

http --print=hb --ignore-stdin --json --pretty format GET localhost:8080/v2/apps?cmd=sleep%2060

#### PUT `/v2/apps/{app_id}`

Change parameters of a running application.  The new application parameters apply only to subsequently created tasks, and currently running tasks are __not__ pre-emptively restarted.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 55' constraints:='[["hostname", "UNIQUE", ""]]' ports:='[9000]' cpus=0.3 mem=9 instances=2

**Response:**

http --print=hb --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 55' constraints:='[["hostname", "UNIQUE", ""]]' ports:='[9000]' cpus=0.3 mem=9 instances=2

:http --ignore-stdin --json --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 60' constraints:='[]' ports:='[0, 0]' cpus=0.1 mem=5 instances=3

#### DELETE `/v2/apps/{app_id}`

Destroy an application. All data about that application will be deleted.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format DELETE localhost:8080/v2/apps/myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

**Response:**

http --print=hb --ignore-stdin --json --pretty format DELETE localhost:8080/v2/apps/myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["https://raw.github.com/mesosphere/marathon/master/README.md"]'

#### GET `/v2/apps/{app_id}/tasks`

List all running tasks for application `app_id`.

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

#### DELETE `/v2/apps/{app_id}/tasks?host={host}&scale={true|false}`

Kill tasks that belong to the application `app_id`, optionally filtered by the task's `host`.

The query parameters `host` and `scale` are both optional.  If `host` is specified, only tasks running on the supplied slave are killed.  If `scale=true` is specified, then the application is scaled down by the number of killed tasks.  The `scale` parameter defaults to `false`.

##### Example

**Request:**

http --print=HB --ignore-stdin --json --pretty format DELETE 'localhost:8080/v2/apps/myApp/tasks?host=mesos.vm&scale=false'

**Response:**

http --print=hb --ignore-stdin --json --pretty format DELETE 'localhost:8080/v2/apps/myApp/tasks?host=mesos.vm&scale=false'

#### DELETE `/v2/apps/{app_id}/tasks/{task_id}?scale={true|false}`

Kill the task with ID `task_id` that belongs to the application `app_id`.

The query parameter `scale` is optional.  If `scale=true` is specified, then the application is scaled down one if the supplied `task_id` exists.  The `scale` parameter defaults to `false`.

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

#### GET `/v1/apps/search?id={app_id}&cmd={command}`

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

#### GET `/v1/apps/{app_id}/tasks`

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

#### GET `/v1/endpoints/{app_id}`

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


#### POST `/v1/tasks/kill?appId={app_id}&host={host}&id={task_id}&scale={true|false}`

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
