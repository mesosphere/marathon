## GET `/v2/groups/{groupId}`

List the application groups with id `groupId`.

### Example

**Request:**

```
GET /v2/groups/my/specific/group HTTP/1.1
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
      "container": null,
      "healthChecks": [ ],
      "version": "2014-05-16T14:39:12.058Z"
    }
  ],
  "version": "2014-05-16T14:39:12.058Z"
}
```


## GET `/v2/groups/{groupId}/versions`

List all versions of group `groupId`.
Note: The root group is `/`. 
It is possible to list all versions of the root group as well: `GET /v2/groups/versions`

### Example

**Request:**

```
GET /v2/groups/my/specific/group/versions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
**Response:**
HTTP/1.1 200 OK
Content-Length: 1297
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)

[
    "2015-05-11T09:48:44.249Z", 
    "2015-05-08T16:43:16.271Z", 
    "2015-05-08T12:15:11.375Z"
]
```

## GET `/v2/groups/{groupId}/versions/{version}`

Get a specific `version` of group `groupId`.
Note: The root group is `/`. 
It is possible to get a specific version of the root group as well: `GET /v2/groups/versions/{version}`

### Example

**Request:**

```
GET /v2/groups/my/specific/group/versions/2015-05-11T09:48:44.249Z HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
**Response:**
HTTP/1.1 200 OK
Content-Length: 1297
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)

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
      "container": null,
      "healthChecks": [ ],
      "version": "2015-05-11T09:48:44.249Z"
    }
  ],
  "version": "2015-05-11T09:48:44.249Z"
}
```

