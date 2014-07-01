## PUT `/v2/groups/{groupId}/version/{version}`

Rollback this group to a specific version, that has been deployed in the past.
All changes to a group will create a new version.
With this endpoint it is possible to an older version of this group.

If there is an upgrade process already in progress, this rollback will be rejected unless the force flag is set.
With the force flag given, a running upgrade is terminated and a new one is started.

The rollback to a version is handled as normal update.
All implications of an update will take place.

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a group_change_success and group_change_failed with
the given version.


### Example

**Request:**

```
PUT /v2/groups/myproduct/version/2014-03-01T23:29:30.158?force=true HTTP/1.1
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```
HTTP/1.1 200 Ok
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```

