# RESTful Marathon

## API Version 2

========

### _Apps_

#### POST `/v2/apps`

**Notes:**

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
    "env": {}, 
    "executor": "", 
    "id": "myApp", 
    "instances": 3, 
    "mem": 256.0, 
    "ports": [
        8080, 
        9000
    ], 
    "uris": [
        "http://www.marioornelas.com/mr-t-dances2.gif"
    ]
}
```

_Constraints:_ Valid constraint operators are one of ["UNIQUE", "CLUSTER", "GROUP_BY"].  For additional information on using placement constraints see [Marathon, a Mesos framework, adds Placement Constraints][constraints].

_Container:_ Additional data passed to the container on application launch.  These consist of an "image" and an array of string options.  The meaning of this data is fully dependent upon the Mesos slave's containerizer.

_Ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeroes as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the task resource.

**Example:**

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --ignore-stdin --json --verbose --pretty format POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["http://www.marioornelas.com/mr-t-dances2.gif"]'

#### GET `/v2/apps`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v2/apps

#### GET `/v2/apps/{app_id}`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v2/apps/myApp

#### GET `/v2/apps?cmd={command}`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v2/apps?cmd=sleep%2060

#### PUT `/v2/apps/{app_id}`

**Example:**

http --ignore-stdin --json --verbose --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 55' constraints:='[["hostname", "UNIQUE", ""]]' ports:='[9000]' cpus=0.3 mem=9 instances=2

:http --ignore-stdin --json --verbose --pretty format PUT localhost:8080/v2/apps/myApp cmd='sleep 60' constraints:='[]' ports:='[0, 0]' cpus=0.1 mem=5 instances=3

#### DELETE `/v2/apps/{app_id}`

**Example:**

http --ignore-stdin --json --verbose --pretty format DELETE localhost:8080/v2/apps/myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["http://www.marioornelas.com/mr-t-dances2.gif"]'

#### DELETE `/v2/apps/{app_id}/tasks?host={host}&scale={true|false}`

**Notes:**

The query parameters `host` and `scale` are both optional.  If `host` is specified, only tasks running on the supplied slave are killed.  If `scale=true` is specified, then the application is scaled down by the number of killed tasks.  The `scale` parameter defaults to `false`.

**Example:**

http --ignore-stdin --json --verbose --pretty format DELETE localhost:8080/v2/apps/myApp/tasks?host=mesos.vm&scale=false

#### DELETE `/v2/apps/{app_id}/tasks/{task_id}?scale={true|false}`

**Notes:**

The query parameter `scale` is optional.  If `scale=true` is specified, then the application is scaled down one if supplied `task_id` exists.  The `scale` parameter defaults to `false`.

**Example:**

```
DELETE /v2/apps/myApp/tasks/myApp_3-1389916890411 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



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

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v2/apps/myApp

## API Version 1 _(DEPRECATED)_

========

### _Apps_

#### POST `/v1/apps/start`

**Example:**

:http --ignore-stdin DELETE localhost:8080/v2/apps/myApp

http --ignore-stdin --json --verbose --pretty format POST localhost:8080/v1/apps/start id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["http://www.marioornelas.com/mr-t-dances2.gif"]'

#### GET `/v1/apps`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/apps

#### POST `/v1/apps/stop`

**Example:**

http --ignore-stdin --json --verbose --pretty format POST localhost:8080/v1/apps/stop id=myApp

:http --ignore-stdin POST localhost:8080/v2/apps id=myApp cmd='sleep 60' instances=3 mem=5 cpus=0.1 ports:='[0, 0]' uris:='["http://www.marioornelas.com/mr-t-dances2.gif"]'

#### POST `/v1/apps/scale`

**Example:**

http --ignore-stdin --json --verbose --pretty format POST localhost:8080/v1/apps/scale id=myApp instances=4

:http --ignore-stdin PUT localhost:8080/v2/apps id=myApp instances=3

#### GET `/v1/apps/search?id={app_id}&cmd={command}`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/apps/search?id=myApp

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/apps/search?cmd=sleep%2060

#### GET `/v1/apps/{app_id}/tasks`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/apps/myApp/tasks

### _Endpoints_

#### GET `/v1/endpoints`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/endpoints

#### GET `/v1/endpoints/{app_id}`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/endpoints/myApp

### Tasks

#### GET `/v1/tasks`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/tasks


#### POST `/v1/tasks/kill?appId={app_id}&host={host}&id={task_id}&scale={true|false}`

**Example:**

http --ignore-stdin --json --verbose --pretty format POST localhost:8080/v1/tasks/kill?appId=myApp

### _Debug_

#### GET `/v1/debug/isLeader`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/debug/isLeader

#### GET `/v1/debug/leaderUrl`

**Example:**

http --ignore-stdin --json --verbose --pretty format GET localhost:8080/v1/debug/leaderUrl

[constraints]: http://mesosphere.io/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints