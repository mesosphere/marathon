## GET `/v2/groups`

List all available groups.

### Example

**Request:**

```
GET /v2/groups/product/service HTTP/1.1
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
[
    {
        "id": "/product/service",
        "apps": [
            {
                "id": "myApp",
                "cmd": "ruby app.rb",
                "env": {},
                "instances": 4,
                "cpus": 0.2,
                "mem": 128.0,
                "executor": "//cmd",
                "constraints": [],
                "uris": [],
                "ports": [19970],
                "container": null,
                "healthChecks": [
                    {
                        "path": "/health",
                        "protocol": "HTTP",
                        "portIndex": 0,
                        "initialDelaySeconds": 15,
                        "intervalSeconds": 5,
                        "timeoutSeconds": 15
                    }
                ],
                "version": "2014-05-16T14:39:12.058Z"
            }
        ],
        "version": "2014-05-16T14:39:12.058Z"
    }
]
```
