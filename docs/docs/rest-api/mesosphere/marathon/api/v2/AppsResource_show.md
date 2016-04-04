#### GET `/v2/apps/{appId}?embed=...`

List the application with id `appId`.

The response includes some status information besides the current configuration of the app.

You can specify optional embed arguments, to get more embedded information.

##### taskRunning (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=app.counts" as a query parameter.

The number of tasks running for this application definition.

##### tasksStaged (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=app.counts" as a query parameter.

The number of tasks staged to run.

##### tasksHealthy (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=app.counts" as a query parameter.

The number of tasks which are healthy.

##### tasksUnhealthy (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=app.counts" as a query parameter.

The number of tasks which are unhealthy.

##### taskStats (Object) <span class="label label-default">v0.11</span>

Provides task statistics. The "taskStats" object only gets embedded into the app JSON if 
you pass an `embed=app.taskStats` query argument.
 
Task statistics are provided for the following groups of tasks. If no tasks for the group exist, no statistics are
offered:

* `"withLatestConfig"` contains statistics about all tasks that run with the same config as the latest app version.
* `"startedAfterLastScaling"` contains statistics about all tasks that were started after the last scaling or
  restart operation.
* `"withOutdatedConfig"` contains statistics about all tasks that were started before the last config change which
  was not simply a restart or scaling operation.
* `"totalSummary"` contains statistics about all tasks.

Example JSON:

```javascript
{
  // ...
  "taskStats": {
    {
      "startedAfterLastScaling" : {
        "stats" : {
          "counts" : { // equivalent to tasksStaged, tasksRunning, tasksHealthy, tasksUnhealthy
            "staged" : 1,
            "running" : 100,
            "healthy" : 90,
            "unhealthy" : 4
          },
          // "lifeTime" is only included if there are running tasks.
          "lifeTime" : { 
            // Measured from `"startedAt"` (timestamp of the Mesos TASK_RUNNING status update) of each running task 
            // until now.
            "averageSeconds" : 20.0,
            "medianSeconds" : 10.0
          }
        }
      },
      "withLatestConfig" : {
        "stats" : { /* ... same structure as above ... */ }
      },
      "withOutdatedConfig" : {
        "stats" : { /* ... same structure as above ... */ }
      },
      "totalSummary" : {
        "stats" : { /* ... same structure as above ... */ }
      }
    }
  }
  // ...
}
```

The calculation of these statistics is currently performed for every request and expensive if you have
very many tasks.

##### deployments (Array of Objects)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=app.deployments" as a query parameter.

A list of currently running deployments that affect this application.
If this array is nonempty, then this app is locked for updates.

The objects in the list only contain the id of the deployment, e.g.:

```javascript
{
    "id": "44c4ed48-ee53-4e0f-82dc-4df8b2a69057"
}
```

##### readiness (Array of Objects)

A list of readiness check results.
Only available during a deployment in the start phase.
 
The object in the list contains a readiness check in the format of:
 
 ```javascript
 {
     "name": "myReadyCheck",
     "taskId": "foo.c3c80be4-f780-11e5-8d32-e24449f9e499",
     "ready": false,
     "lastResponse": {
       "status": 500,
       "contentType": "application/json",
       "body": "{}"
     }
   }
 ```

##### lastTaskFailure (Object)

Information about the last task failure for debugging purposes.

This information only gets embedded if you specify the "embed=app.lastTaskFailure" query
parameter.

##### version

The version of the current app definition

##### versionInfo.lastConfigChangeAt (Timestamp as String)

`"lastConfigChangeAt"` contains the time stamp of the last change to this app which was not simply a scaling
or a restarting configuration.

##### versionInfo.lastScalingAt (Timestamp as String)

`"lastScalingAt"` contains the time stamp of the last change including changes like scaling or 
restarting the app. Since our versions are time based, this is currently equal to `"version"`.


##### Example

**Request:**

```http
GET /v2/apps/myapp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "app": {
        "args": null,
        "backoffFactor": 1.15,
        "backoffSeconds": 1,
        "maxLaunchDelaySeconds": 3600,
        "cmd": "python toggle.py $PORT0",
        "constraints": [],
        "container": null,
        "cpus": 0.2,
        "dependencies": [],
        "deployments": [
            {
                "id": "44c4ed48-ee53-4e0f-82dc-4df8b2a69057"
            }
        ],
        "disk": 0.0,
        "env": {},
        "executor": "",
        "healthChecks": [
            {
                "command": null,
                "gracePeriodSeconds": 5,
                "intervalSeconds": 10,
                "maxConsecutiveFailures": 3,
                "path": "/health",
                "portIndex": 0,
                "protocol": "HTTP",
                "timeoutSeconds": 10
            },
            {
                "command": null,
                "gracePeriodSeconds": 5,
                "intervalSeconds": 10,
                "maxConsecutiveFailures": 6,
                "path": "/machinehealth",
                "overridePort": 3333,
                "protocol": "HTTP",
                "timeoutSeconds": 10
            }
        ],
        "id": "/toggle",
        "instances": 2,
        "lastTaskFailure": { // soon only for ?embed=lastTaskFailure
            "appId": "/toggle",
            "host": "10.141.141.10",
            "message": "Abnormal executor termination",
            "state": "TASK_FAILED",
            "taskId": "toggle.cc427e60-5046-11e4-9e34-56847afe9799",
            "timestamp": "2014-09-12T23:23:41.711Z",
            "version": "2014-09-12T23:28:21.737Z"
        },
        "mem": 32.0,
        "ports": [
            10000
        ],
        "requirePorts": false,
        "storeUrls": [],
        "tasks": [ // soon only for ?embed=tasks
            {
                "appId": "/toggle",
                "healthCheckResults": [
                    {
                        "alive": true,
                        "consecutiveFailures": 0,
                        "firstSuccess": "2014-09-13T00:20:28.101Z",
                        "lastFailure": null,
                        "lastSuccess": "2014-09-13T00:25:07.506Z",
                        "taskId": "toggle.802df2ae-3ad4-11e4-a400-56847afe9799"
                    },
                    {
                        "alive": true,
                        "consecutiveFailures": 0,
                        "firstSuccess": "2014-09-13T00:20:28.101Z",
                        "lastFailure": null,
                        "lastSuccess": "2014-09-13T00:25:07.506Z",
                        "taskId": "toggle.802df2ae-3ad4-11e4-a400-56847afe9799"
                    }
                ],
                "host": "10.141.141.10",
                "id": "toggle.802df2ae-3ad4-11e4-a400-56847afe9799",
                "ports": [
                    31045
                ],
                "stagedAt": "2014-09-12T23:28:28.594Z",
                "startedAt": "2014-09-13T00:24:46.959Z",
                "version": "2014-09-12T23:28:21.737Z"
            },
            {
                "appId": "/toggle",
                "healthCheckResults": [
                    {
                        "alive": true,
                        "consecutiveFailures": 0,
                        "firstSuccess": "2014-09-13T00:20:28.101Z",
                        "lastFailure": null,
                        "lastSuccess": "2014-09-13T00:25:07.508Z",
                        "taskId": "toggle.7c99814d-3ad4-11e4-a400-56847afe9799"
                    },
                    {
                        "alive": true,
                        "consecutiveFailures": 0,
                        "firstSuccess": "2014-09-13T00:20:28.101Z",
                        "lastFailure": null,
                        "lastSuccess": "2014-09-13T00:25:07.506Z",
                        "taskId": "toggle.802df2ae-3ad4-11e4-a400-56847afe9799"
                    }
                ],
                "host": "10.141.141.10",
                "id": "toggle.7c99814d-3ad4-11e4-a400-56847afe9799",
                "ports": [
                    31234
                ],
                "stagedAt": "2014-09-12T23:28:22.587Z",
                "startedAt": "2014-09-13T00:24:46.965Z",
                "version": "2014-09-12T23:28:21.737Z"
            }
        ],
        "tasksRunning": 2, // soon only for ?embed=counts
        "tasksHealthy": 2, // soon only for ?embed=counts
        "tasksUnhealthy": 0, // soon only for ?embed=counts
        "tasksStaged": 0, // soon only for ?embed=counts
        "upgradeStrategy": {
            "minimumHealthCapacity": 1.0
        },
        "uris": [
            "http://downloads.mesosphere.com/misc/toggle.tgz"
        ],
        "user": null,
        "version": "2014-09-12T23:28:21.737Z",
        "versionInfo": {
            "lastConfigChangeAt": "2014-09-11T02:26:01.135Z",
            "lastScalingAt": "2014-09-12T23:28:21.737Z"
        }
    }
}
```
