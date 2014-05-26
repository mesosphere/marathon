## PUT `/v2/groups/{groupId}`

Change parameters of a deployed application group.  
The new group parameters get applied.

* Changes to application parameters will result in a restart of this application.
* A new application added to the group is started.
* An existing application removed from the group gets stopped.

If there are no changes to the application definition, no restart is triggered.
During restart marathon keeps track, that the configured amount of minimal running instances are _always_ available.

A deployment can run forever. This is the case, when the new application has a problem and does not become healthy.
In this case, human interaction is needed with 2 possible choices:

* Rollback to an existing older version (use the rollback endpoint)
* Update with a newer version of the group which does not have the problems of the old one.

If there is an upgrade process already in progress, a new update will be rejected unless the force flag is set.
With the force flag given, a running upgrade is terminated and a new one is started.


### Example

**Request:**

```
PUT /v2/groups/myProduct HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 176
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{
  "scalingStrategy" : { 
    "minimumHealthCapacity": 0.5 
  },
  "apps":[ 
    {
      "id": "myApp",
      "cmd": "ruby app2.rb",
      "env": {},
      "instances": 6,
      "cpus": 0.2,
      "mem": 128.0,
      "executor": "//cmd",
      "constraints": [],
      "uris": [],
      "ports": [19970],
      "taskRateLimit": 1.0,
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
    }
  ]
}
```

**Response:**

```
HTTP/1.1 204 No Content
Server: Jetty(8.y.z-SNAPSHOT)
```

