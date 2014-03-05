## POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

```json
{
    "cmd": "(env && sleep 300)",
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
    "id": "myApp",
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
}
```


_Constraints:_ Valid constraint operators are one of ["UNIQUE", "CLUSTER", "GROUP_BY"].  For additional information on using placement constraints see [Marathon, a Mesos framework, adds Placement Constraints](http://mesosphere.io/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints).

_Container:_ Additional data passed to the container on application launch.  These consist of an "image" and an array of string options.  The meaning of this data is fully dependent upon the executor.  Furthermore, _it is invalid to pass container options when using the default command executor_.

_Ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeros as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the task resource.

### Example

**Request:**


```
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 273
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "cmd": "env && sleep 60", 
    "constraints": [
        [
            "hostname", 
            "UNIQUE", 
            ""
        ]
    ], 
    "cpus": "0.1", 
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    }, 
    "id": "myApp", 
    "instances": "3", 
    "mem": "5", 
    "ports": [
        0, 
        0
    ], 
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ]
}
```

**Response:**


```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
