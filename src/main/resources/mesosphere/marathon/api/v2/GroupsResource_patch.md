## PATCH `/v2/groups/{groupId}`

Change parameters of a deployed application group.  
The new group parameters get applied.

* Changes to application parameters will result in a restart of this application.
* A new application added to the group is started.
* An existing application removed from the group gets stopped.

If there are no changes to the application definition, no restart is triggered.
During restart marathon keeps track, that the configured amount of minimal running instances are _always_ available.

If the update to the group will result in unhealthy state of the applications, an automatic rollback will take place, 
where the last configuration of the group will be restarted.

### Example

**Request:**

```
PATCH /v2/groups/myProduct HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 176
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{
  "apps" : [ 
    { 
      "id": "myApp", 
      "cmd" : "ruby app2.rb"
    } 
  ]
}
```

**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
```

