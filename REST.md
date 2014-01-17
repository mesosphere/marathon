<!-- AUTO-GENERATED FILE; DO NOT MODIFY -->
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
        "image": "s3://path/to/myImage",
        "options": ["-optionA", "-optionB", "-optionC"]
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

_Ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeroes as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the 

**Example:**


```
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 154
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
        "http://www.marioornelas.com/mr-t-dances2.gif"
    ]
}

HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```

#### GET `/v2/apps`

**Example:**

```
GET /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [
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
                13423, 
                16645
            ], 
            "uris": [
                "http://www.marioornelas.com/mr-t-dances2.gif"
            ]
        }
    ]
}
```

#### GET `/v2/apps/{app_id}`

**Example:**

```
GET /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "app": {
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
            13423, 
            16645
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_1-1389987788758", 
                "ports": [
                    31071, 
                    31072
                ], 
                "stagedAt": "2014-01-17T19:43+0000", 
                "startedAt": null
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389987782750", 
                "ports": [
                    31384, 
                    31385
                ], 
                "stagedAt": "2014-01-17T19:43+0000", 
                "startedAt": null
            }
        ], 
        "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
    }
}
```

#### GET `/v2/apps?cmd={command}`

**Example:**

```
GET /v2/apps?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [
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
                13423, 
                16645
            ], 
            "uris": [
                "http://www.marioornelas.com/mr-t-dances2.gif"
            ]
        }
    ]
}
```

#### PUT `/v2/apps/{app_id}`

**Example:**

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

HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### DELETE `/v2/apps/{app_id}`

**Example:**

```
DELETE /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### DELETE `/v2/apps/{app_id}/tasks?host={host}&scale={true|false}`

**Notes:**

The query parameters `host` and `scale` are both optional.  If `host` is specified, only tasks running on the supplied slave are killed.  If `scale=true` is specified, then the application is scaled down by the number of killed tasks.  The `scale` parameter defaults to `false`.

**Example:**

```

```

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

DELETE /v2/apps/myApp/tasks?host=mesos.vm HTTP/1.1
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
    "tasks": [
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389987807785", 
            "ports": [
                31903, 
                31904
            ], 
            "stagedAt": "2014-01-17T19:43+0000", 
            "startedAt": null
        }
    ]
}```
GET /v2/apps/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "app": {
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
            17552, 
            11545
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389987812787", 
                "ports": [
                    31209, 
                    31210
                ], 
                "stagedAt": "2014-01-17T19:43+0000", 
                "startedAt": null
            }
        ], 
        "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
    }
}
```

## API Version 1 _(DEPRECATED)_

========

### _Apps_

#### POST `/v1/apps/start`

**Example:**


```
POST /v1/apps/start HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 154
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
        "http://www.marioornelas.com/mr-t-dances2.gif"
    ]
}

HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```

#### GET `/v1/apps`

**Example:**

```
GET /v1/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



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
            17628, 
            16142
        ], 
        "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
    }
]
```

#### POST `/v1/apps/stop`

**Example:**

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

HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### POST `/v1/apps/scale`

**Example:**

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

HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```


#### GET `/v1/apps/search?id={app_id}&cmd={command}`

**Example:**

```
GET /v1/apps/search?id=myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



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
        "instances": 4, 
        "mem": 5.0, 
        "ports": [
            15557, 
            13917
        ], 
        "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
    }
]
```

**Example:**

```
GET /v1/apps/search?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



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
        "instances": 4, 
        "mem": 5.0, 
        "ports": [
            15557, 
            13917
        ], 
        "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
    }
]
```

#### GET `/v1/apps/{app_id}/tasks`

**Example:**

```
GET /v1/apps/myApp/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "myApp": [
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389987844858", 
            "ports": [
                31790, 
                31791
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389987833843", 
            "ports": [
                31128, 
                31129
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_3-1389987849871", 
            "ports": [
                31554, 
                31555
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389987838851", 
            "ports": [
                31513, 
                31514
            ]
        }
    ]
}
```

### _Endpoints_

#### GET `/v1/endpoints`

**Example:**

```
GET /v1/endpoints HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

[
    {
        "id": "myApp", 
        "instances": [
            {
                "host": "mesos.vm", 
                "id": "myApp_2-1389987844858", 
                "ports": [
                    31790, 
                    31791
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389987833843", 
                "ports": [
                    31128, 
                    31129
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_3-1389987849871", 
                "ports": [
                    31554, 
                    31555
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_1-1389987838851", 
                "ports": [
                    31513, 
                    31514
                ]
            }
        ], 
        "ports": [
            15557, 
            13917
        ]
    }
]
```

#### GET `/v1/endpoints/{app_id}`

**Example:**

```
GET /v1/endpoints/myApp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "id": "myApp", 
    "instances": [
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389987844858", 
            "ports": [
                31790, 
                31791
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389987833843", 
            "ports": [
                31128, 
                31129
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_3-1389987849871", 
            "ports": [
                31554, 
                31555
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389987838851", 
            "ports": [
                31513, 
                31514
            ]
        }
    ], 
    "ports": [
        15557, 
        13917
    ]
}
```

### Tasks

#### GET `/v1/tasks`

**Example:**

```
GET /v1/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "myApp": [
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389987844858", 
            "ports": [
                31790, 
                31791
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389987833843", 
            "ports": [
                31128, 
                31129
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_3-1389987849871", 
            "ports": [
                31554, 
                31555
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389987838851", 
            "ports": [
                31513, 
                31514
            ]
        }
    ]
}
```


#### POST `/v1/tasks/kill?appId={app_id}&host={host}&id={task_id}&scale={true|false}`

**Example:**

```
POST /v1/tasks/kill?appId=myApp HTTP/1.1
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

[]
```

### _Debug_

#### GET `/v1/debug/isLeader`

**Example:**

```
GET /v1/debug/isLeader HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

true
```

#### GET `/v1/debug/leaderUrl`

**Example:**

```
GET /v1/debug/leaderUrl HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2



HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

mesos:8080
```

