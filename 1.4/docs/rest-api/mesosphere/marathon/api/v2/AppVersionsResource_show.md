## GET `/v2/apps/{appId}/versions/{version}`

List the configuration of the application with id `appId` at version `version`.

### Example

**Request:**

```
GET /v2/apps/product/service/myapp/versions/2014-03-01T23:17:50.295Z HTTP/1.1
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
    "id": "/product/service/myapp", 
    "cmd": "sleep 60", 
    "constraints": [], 
    "container": null, 
    "cpus": 0.1, 
    "env": {}, 
    "executor": "", 
    "instances": 4, 
    "mem": 5.0, 
    "ports": [
        18027, 
        13200
    ], 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ], 
    "version": "2014-03-01T23:17:50.295Z"
}
```
