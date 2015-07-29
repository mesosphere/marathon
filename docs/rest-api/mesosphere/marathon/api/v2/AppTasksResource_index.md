## GET `/v2/apps/{app_id}/tasks`

List all running tasks for application `app_id`.

### Example (as JSON)

**Request:**

```
GET /v2/apps/product/myapp/tasks HTTP/1.1
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
            "host": "mesos.vm", 
            "id": "myapp_0-1390455060411", 
            "ports": [
                31190, 
                31191
            ], 
            "stagedAt": "2014-01-23T05:31+0000", 
            "startedAt": null
        }
    ]
}
```

### Example for nested paths

**Request:**

```
GET /v2/apps/product/*/tasks HTTP/1.1
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
            "host": "mesos.vm", 
            "id": "myapp_0-1390455060411", 
            "ports": [
                31190, 
                31191
            ], 
            "stagedAt": "2014-01-23T05:31+0000", 
            "startedAt": null
        }
    ]
}
```

### Example (as text)

**Request:**

```
GET /v2/apps/product/myapp/tasks HTTP/1.1
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

/product/myapp   15658   mesos.vm:31190  mesos.vm:31654  
/product/myapp   10180   mesos.vm:31191  mesos.vm:31655  

```
