<!-- AUTO-GENERATED FILE; DO NOT MODIFY -->
# RESTful Marathon

## API Version 2

========

### _Apps_

#### POST `/v2/apps`

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
            "cpus": 0.1, 
            "env": {}, 
            "executor": "", 
            "id": "myApp", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                19628, 
                15035
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
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            19628, 
            15035
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389918269241", 
                "ports": [
                    31178, 
                    31179
                ], 
                "stagedAt": "2014-01-17T00:24+0000", 
                "startedAt": "2014-01-17T00:24+0000"
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_1-1389918275247", 
                "ports": [
                    31681, 
                    31682
                ], 
                "stagedAt": "2014-01-17T00:24+0000", 
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
            "cpus": 0.1, 
            "env": {}, 
            "executor": "", 
            "id": "myApp", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                19628, 
                15035
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


#### DELETE `/v2/apps/{app_id}/tasks`

```
{"tasks":[]}
```

#### DELETE `/v2/apps/{app_id}/tasks/{task_id}`

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
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            18370, 
            12328
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389918295277", 
                "ports": [
                    31617, 
                    31618
                ], 
                "stagedAt": "2014-01-17T00:24+0000", 
                "startedAt": null
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_1-1389918301285", 
                "ports": [
                    31663, 
                    31664
                ], 
                "stagedAt": "2014-01-17T00:25+0000", 
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
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            13813, 
            11498
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
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 4, 
        "mem": 5.0, 
        "ports": [
            14223, 
            16876
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
        "cpus": 0.1, 
        "env": {}, 
        "executor": "", 
        "id": "myApp", 
        "instances": 4, 
        "mem": 5.0, 
        "ports": [
            14223, 
            16876
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
            "id": "myApp_3-1389918336356", 
            "ports": [
                31874, 
                31875
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389918326338", 
            "ports": [
                31015, 
                31016
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389918321328", 
            "ports": [
                31018, 
                31019
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389918331345", 
            "ports": [
                31005, 
                31006
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
                "id": "myApp_3-1389918336356", 
                "ports": [
                    31874, 
                    31875
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_1-1389918326338", 
                "ports": [
                    31015, 
                    31016
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1389918321328", 
                "ports": [
                    31018, 
                    31019
                ]
            }, 
            {
                "host": "mesos.vm", 
                "id": "myApp_2-1389918331345", 
                "ports": [
                    31005, 
                    31006
                ]
            }
        ], 
        "ports": [
            14223, 
            16876
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
            "id": "myApp_3-1389918336356", 
            "ports": [
                31874, 
                31875
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389918326338", 
            "ports": [
                31015, 
                31016
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389918321328", 
            "ports": [
                31018, 
                31019
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389918331345", 
            "ports": [
                31005, 
                31006
            ]
        }
    ], 
    "ports": [
        14223, 
        16876
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
            "id": "myApp_3-1389918336356", 
            "ports": [
                31874, 
                31875
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1389918326338", 
            "ports": [
                31015, 
                31016
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1389918321328", 
            "ports": [
                31018, 
                31019
            ]
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_2-1389918331345", 
            "ports": [
                31005, 
                31006
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
