## GET `/v2/apps/{app_id}`

List the application with id `app_id`.

### Example

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
            17988, 
            13669
        ], 
        "tasks": [
            {
                "host": "mesos.vm", 
                "id": "myApp_0-1390455027370", 
                "ports": [
                    31340, 
                    31341
                ], 
                "stagedAt": "2014-01-23T05:30+0000", 
                "startedAt": "2014-01-23T05:30+0000"
            }
        ], 
        "uris": [
            "https://raw.github.com/mesosphere/marathon/master/README.md"
        ]
    }
}
```
