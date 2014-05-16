## POST `/v2/groups`

Create and start a new application group.

The JSON format of a group resource is as follows:

```json
{
  "id" : "groupName",
  "scalingStrategy" : { "minimumHealthCapacity": 0.5 },
  "apps":[
     {
         "id": "myApp",
         "cmd": "sleep 30",
         "constraints": [
             ["attribute", "OPERATOR", "value"]
         ],
         "container": {
             "image": "docker:///zaiste/postgresql",
             "options": ["-e", "X=7"]
         },
         "cpus": 2,
         "env": {
             "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
         },
         "executor": "",
         "instances": 3,
         "mem": 256.0,
         "ports": [
             8080,
             9000
         ],
         "taskRateLimit": 1.0,
         "tasksRunning": 3, 
         "tasksStaged": 0, 
         "uris": [
             "https://raw.github.com/mesosphere/marathon/master/README.md"
         ], 
         "version": "2014-03-01T23:29:30.158Z"
     },  
     {
         "id": "myApp2",
         "cmd": "someExecutable",
         "cpus": 2,
         "instances": 3,
         "mem": 256.0,
         "version": "2014-03-01T23:29:30.158Z"
     } 
  ],
  "version": "2014-03-01T23:29:30.158Z"
}
```

The minimumHealthCapacity (value range [0..1]) defines the percentage of healthy nodes during restarts.
E.g.: A minimumHealthCapacity=0.5 with an app with 10 running instances: at least 5 instances of this application 
will be always available.


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
