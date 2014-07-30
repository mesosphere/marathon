## GET `/v2/groups/{groupId}`

List the application groups with id `groupId`.

### Example

**Request:**

```
GET /v2/groups/my/specificgroup HTTP/1.1
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
  "id": "/my/specific/group",
  "apps": [
    {
      "id": "/my/specific/group/app1",
      "cmd": "ruby app.rb",
      "env": {},
      "instances": 4,
      "cpus": 0.2,
      "mem": 128.0,
      "executor": "//cmd",
      "constraints": [],
      "uris": [],
      "ports": [19970],
      "taskRateLimit": 1.0,
      "container": null,
      "healthChecks": [ ],
      "version": "2014-05-16T14:39:12.058Z"
    }
  ],
  "version": "2014-05-16T14:39:12.058Z"
}
```
