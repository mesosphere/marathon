## POST `/v2/apps`

Create and start a new application.

The full JSON format of an application resource is as follows:

```json
{
    "id": "/product/service/myApp",
    "cmd": "(env && sleep 300)",
    "container": {
        "image": "docker:///zaiste/postgresql",
        "options": ["-e", "X=7"]
    },
    "cpus": 1.5,
    "mem": 256.0,
    "env": {
        "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
    },
    "executor": "",
    "constraints": [
        ["attribute", "OPERATOR", "value"]
    ],
    "instances": 3,
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
    "healthChecks": [{
        "protocol": "http",
        "path": "/",
        "portIndex": 0,
        "gracePeriodSeconds": 15,
        "intervalSeconds": 10,
        "timeoutSeconds": 20,
        "maxConsecutiveFailures": 3
    },{
        "protocol": "tcp",
        "portIndex": 0,
        "gracePeriodSeconds": 15,
        "intervalSeconds": 10,
        "timeoutSeconds": 20,
        "maxConsecutiveFailures": 3
    }],
    "dependencies": ["/product/db/mongo", "/product/db", "../../db"],
    "upgradeStrategy": {
        "minimumHealthCapacity": 0.5,
        "maximumRunningFactor": 2.0
    },
    "version": "2014-03-01T23:29:30.158Z"
}
```


_id:_ [String] The identifier of the application. This identifier is a path. If the identifier does not start with a slash, 
than it is interpreted as relative path and will be translated into an absolute path.

_cmd:_ [String] The command that is executed on the shell.

_container:_ [Container] Additional data passed to the container on application launch.  These consist of an "image" and an array of string options.  The meaning of this data is fully dependent upon the executor.  Furthermore, _it is invalid to pass container options when using the default command executor_.

_cpus:_: [Float] the number of CPU`s this application needs per instance. This number does not have to be integer, but can be a fraction.

_mem:_: [Float] the amount of memory in MB that is needed for the application per instance. 

_env:_: [Environent] key/value pairs that get added to the environment variables of the process to start.

_executor:_ [String] the executor to use to launch this application. Different executors are available. The simplest one (and the default if none is given) is //cmd, which takes the cmd and executes that on the shell level.

_constraints:_ [Array[String]] Valid constraint operators are one of ["UNIQUE", "LIKE", "CLUSTER", "GROUP_BY"].  For additional information on using placement constraints see [Marathon, a Mesos framework, adds Placement Constraints](http://mesosphere.io/2013/11/22/marathon-a-mesos-framework-adds-placement-constraints).

_instances:_: The number of instances of this application to start. Please note: this number can be changed everytime as needed to scale the application.

_ports:_ An array of required port resources on the host.  To generate one or more arbitrary free ports for each application instance, pass zeros as port values.  Each port value is exposed to the instance via environment variables `$PORT0`, `$PORT1`, etc.  Ports assigned to running instances are also available via the task resource.

_taskRateLimit:_  Number of new tasks this app may spawn per second in response to terminated tasks. This prevents frequently failing apps from spamming the cluster.

_taskRunning:_ [Integer] the number of tasks running for this application definition. This parameter can not be set.

_tasksStaged:_ [Integer] the number of tasks staged to run. This parameter can not be set.

_uris:_ [Array[String]] uris defined here are resolved, before the application gets started. If the application has external dependencies, they should be defined here.

_healthChecks:_ [[Array[HealthCheck]] healt checks are sensing elements to the health of the application. An application is considered healthy, if all instances are running and all health checks pass. To use this feature, the application needs either a http endpoint or a tcp socket to listen to.

_dependencies:_ [Array[String]] an application can have dependencies to other applications. The correct dependecy order can be insured via the start/stops and especially upgrades of the application.
E.g. an application /a relies on the services /b which itself relies on /c. To start all 3 applications, first /c is started than /b than /a.

_upgradeStrategy:_ [UpgradeStrategy] during an upgrade all instances of an application get replaced by a new version. 
The minimumHealthCapacity defines the minimum number of healthy nodes, that do not sacrifice overall application purpose. 
It is a number between 0 and 1 which is multiplied with the instance count. 
The default minimumHealthCapacity is 1, which means no old instance can be stopped, before all new instances are deployed. 
A value of 0.5 means that an upgrade can be deployed side by side, by taking half of the instances down in the first step, 
deploy half of the new version and than take the other half down and deploy the rest. 
A value of 0 means take all instances down immediately and replace with the new application.

_version:_ [String] ISODate string, which shows the last change of the application. This parameter can not be set.

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
    "id": "myapp", 
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
