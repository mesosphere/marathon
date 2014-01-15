# RESTful Marathon

## API Version 1

========

### _Apps_

#### POST `/v1/apps/start`

**Example Request:**

    POST /v1/apps/start HTTP/1.1
    Accept: application/json
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 154
    Content-Type: application/json; charset=utf-8
    Host: 10.141.141.10:8080
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

**Response:**

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### GET `/v1/apps`

**Example Request:**

    GET /v1/apps HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

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
        "ports": [15438, 17842],
        "uris": [
          "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
      }
    ]

#### POST `/v1/apps/stop`

**Example Request:**

    POST /v1/apps/stop HTTP/1.1
    Accept: application/json
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 15
    Content-Type: application/json; charset=utf-8
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    {
      "id": "myApp"
    }

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### POST `/v1/apps/scale`

**Example Request:**

    POST /v1/apps/scale HTTP/1.1
    Accept: application/json
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 33
    Content-Type: application/json; charset=utf-8
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

    {
      "id": "myApp", 
      "instances": "4"
    }

**Response:**

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### GET `/v1/apps/search?id={app_id}&cmd={command}`

**Example Request:**

    GET /v1/apps/search?id=myApp HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

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
          16305, 
          13332
        ], 
        "uris": [
          "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
      }
    ]

**Example Request:**

    GET /v1/apps/search?cmd=sleep%2060 HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

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
          16305, 
          13332
        ], 
        "uris": [
          "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
      }
    ]

#### GET `/v1/apps/{app_id}/tasks`

**Example Request:**

    GET /v1/apps/myApp/tasks HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    {
      "myApp": [
        {
          "host": "mesos.vm", 
          "id": "myApp_3-1389826894823", 
          "ports": [
            31996, 
            31997
          ]
        },
        {
          "host": "mesos.vm", 
          "id": "myApp_3-1389826897827", 
          "ports": [
            31992, 
            31993
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389826954950", 
          "ports": [
            31934, 
            31935
          ]
        }
      ]
    }

### _Endpoints_

#### GET `/v1/endpoints`

**Example Request:**

    GET /v1/endpoints HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: text/plain
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    myApp_16305 16305 mesos.vm:31008 mesos.vm:31304 mesos.vm:31707
    myApp_13332 13332 mesos.vm:31009 mesos.vm:31305 mesos.vm:31708 
    myApp2_17049 17049 mesos.vm:31122 mesos.vm:31006 mesos.vm:31715
    myApp2_19759 19759 mesos.vm:31123 mesos.vm:31007 mesos.vm:31716

#### GET `/v1/endpoints/{app_id}`

**Example Request:**

    GET /v1/endpoints/myApp HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    {
      "id": "myApp", 
      "instances": [
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827227412", 
          "ports": [
            31947, 
            31948
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827170331", 
          "ports": [
            31727, 
            31728
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827167326", 
          "ports": [
            31000, 
            31001
          ]
        }
      ], 
      "ports": [
        16305, 
        13332
      ]
    }

### Tasks

#### GET `/v1/tasks`

**Example Request:**

    GET /v1/tasks HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    {
      "myApp": [
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827303553", 
          "ports": [
            31989, 
            31990
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827295546", 
          "ports": [
            31957, 
            31958
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp_2-1389827306556", 
          "ports": [
            31969, 
            31970
          ]
        }
      ], 
      "myApp2": [
        {
          "host": "mesos.vm", 
          "id": "myApp2_2-1389827278515", 
          "ports": [
            31949, 
            31950
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp2_2-1389827283521", 
          "ports": [
            31961, 
            31962
          ]
        }, 
        {
          "host": "mesos.vm", 
          "id": "myApp2_2-1389827288530", 
          "ports": [
            31979, 
            31980
          ]
        }
      ]
    }

#### POST `/v1/tasks/kill?appId={app_id}&host={host}&id={task_id}&scale={true|false}`

**Example Request:**

    POST /v1/tasks/kill?id=myApp2_2-1389827288530 HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 0
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked
    
    []

### _Debug_

#### GET `/v1/debug/isLeader`

**Example Request:**

    GET /v1/debug/isLeader HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked
    
    true

#### GET `/v1/debug/leaderUrl`

**Example Request:**

    GET /v1/debug/leaderUrl HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked
    
    mesos:8080

## API Version 2

========

### _Apps_

#### POST `/v2/apps`

**Example Request:**

    POST /v2/apps HTTP/1.1
    Accept: application/json
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 154
    Content-Type: application/json; charset=utf-8
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

    {
      "cmd": "sleep 60", 
      "cpus": "0.1", 
      "id": "myApp", 
      "instances": "3", 
      "mem": "5", 
      "ports": [0, 0],
      "uris": [
        "http://www.marioornelas.com/mr-t-dances2.gif"
      ]
    }

**Response:**

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### GET `/v2/apps`

**Example Request:**

    GET /v2/apps HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

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
            17379, 
            11650
          ], 
          "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
          ]
        }
      ]
    }

#### GET `/v2/apps/{app_id}`

**Example Request:**

GET /v2/apps/myApp HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Host: 10.141.141.10:8080
User-Agent: HTTPie/0.7.2

**Response:**

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
          17379, 
          11650
        ], 
        "tasks": [
          {
            "host": "mesos.vm", 
            "id": "myApp_1-1389828037892", 
            "ports": [
              31348, 
              31349
            ]
          }, 
          {
            "host": "mesos.vm", 
            "id": "myApp_1-1389828039895", 
            "ports": [
              31966, 
              31967
            ]
          }, 
          {
            "host": "mesos.vm", 
            "id": "myApp_2-1389828044903", 
            "ports": [
              31072, 
              31073
            ]
          }
        ], 
        "uris": [
          "http://www.marioornelas.com/mr-t-dances2.gif"
        ]
      }
    }

#### GET `/v2/apps?cmd={command}`

**Example Request:**

    GET /v2/apps?cmd=sleep%2060 HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

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
            17379, 
            11650
          ], 
          "uris": [
            "http://www.marioornelas.com/mr-t-dances2.gif"
          ]
        }
      ]
    }

#### PUT `/v2/apps/{app_id}`

**Example Request:**

    PUT /v2/apps/myApp HTTP/1.1
    Accept: application/json
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 109
    Content-Type: application/json; charset=utf-8
    Host: 10.141.141.10:8080
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
      "mem": "9"
    }

**Response:**

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### DELETE `/v2/apps/{app_id}`

**Example Request:**

    DELETE /v2/apps/myApp HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 0
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 204 No Content
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)

#### DELETE `/v2/apps/{app_id}/tasks`

**Example Request:**

    DELETE /v2/apps/myApp/tasks HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 0
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    {
      "tasks": []
    }

#### DELETE `/v2/apps/{app_id}/tasks/{task_id}`

**Example Request:**

    DELETE /v2/apps/myApp/tasks/myApp_2-1389828508920 HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Content-Length: 0
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: application/json
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked
    
    {
      "task": {
        "host": "mesos.vm", 
        "id": "myApp_2-1389828508920", 
        "ports": [
          31542, 
          31543
        ]
      }
    }

### _Tasks_

#### GET `/v2/tasks`

**Example Request:**

    GET /v2/tasks HTTP/1.1
    Accept: */*
    Accept-Encoding: gzip, deflate, compress
    Host: 10.141.141.10:8080
    User-Agent: HTTPie/0.7.2

**Response:**

    HTTP/1.1 200 OK
    Content-Type: text/plain
    Server: Jetty(8.y.z-SNAPSHOT)
    Transfer-Encoding: chunked

    myApp_17434 17434 mesos.vm:31010 mesos.vm:31086 mesos.vm:31004 
    myApp_14401 14401 mesos.vm:31011 mesos.vm:31087 mesos.vm:31005
