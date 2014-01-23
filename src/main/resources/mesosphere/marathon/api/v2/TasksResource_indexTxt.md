## GET `/v2/tasks`

List tasks of all running applications.

### Example (as JSON)

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
    "myApp": [
        {
            "host": "mesos.vm", 
            "id": "myApp_1-1390455080443", 
            "ports": [
                31925, 
                31926
            ], 
            "stagedAt": "2014-01-23T05:31+0000", 
            "startedAt": null
        }, 
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1390455075436", 
            "ports": [
                31755, 
                31756
            ], 
            "stagedAt": "2014-01-23T05:31+0000", 
            "startedAt": "2014-01-23T05:31+0000"
        }
    ]
}
```

### Example (as text)

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

myApp   15658   mesos.vm:31938  mesos.vm:31925  mesos.vm:31755  
myApp   10180   mesos.vm:31939  mesos.vm:31926  mesos.vm:31756  

```