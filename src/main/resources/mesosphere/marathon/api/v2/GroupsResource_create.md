## POST `/v2/groups`

Create and start a new application group.
Application groups can contain other application groups.
An application group can either hold other groups or applications, but can not be mixed in one.

The JSON format of a group resource is as follows:

```json
{
  "id": "product",
  "groups": [{
    "id": "service",
    "groups": [{
      "id": "us-east",
      "apps": [{
        "id": "app1",
        "cmd": "someExecutable"
      },  
      {
        "id": "app2",
        "cmd": "someOtherExecutable"
      }]
    }],
    "dependencies": ["/product/database", "../backend"]
  }
]
"version": "2014-03-01T23:29:30.158Z"
}
```

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a group_change_success and group_change_failed with
the given version.


### Example

**Request:**

```
POST /v2/groups HTTP/1.1
User-Agent: curl/7.35.0
Accept: application/json
Host: localhost:8080
Content-Type: application/json
Content-Length: 273
{
  "id" : "product",
  "apps":[ 
    {
      "id": "myapp",
      "cmd": "ruby app2.rb",
      "instances": 1
    }
  ]
}
```

**Response:**


```
HTTP/1.1 201 Created
Location: http://localhost:8080/v2/groups/product
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```
