## GET `/v2/apps/{appId}`

List the application with id `appId`.

### Example

**Request:**

```
GET /v2/apps/myapp HTTP/1.1
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
        "id": "/myapp", 
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
        "instances": 3, 
        "mem": 5.0, 
        "ports": [
            15092, 
            14566
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1393717344297", 
                "ports": [
                    31651, 
                    31652
                ], 
                "stagedAt": "2014-03-01T23:42:24.298Z", 
                "startedAt": "2014-03-01T23:42:29.902Z"
            }
        ], 
        "tasksRunning": 1, 
        "tasksStaged": 0, 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ], 
        "version": "2014-03-01T23:42:20.938Z"
    }
}
```
