## PUT `/v2/groups/{groupId}`

A group can be scaled. 
The scaling effects apps directly in the group as well as all transitive applications referenced by subgroups of this group.
The scaling factor is applied to each individual instance count of each application.

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a group_change_success and group_change_failed with
the given version.


### Example

**Request:**

```
PUT /v2/groups/product/service HTTP/1.1
Content-Length: 123
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{ "scaleBy": 1.5 }
```

**Response:**

```
HTTP/1.1 200 Ok
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```

