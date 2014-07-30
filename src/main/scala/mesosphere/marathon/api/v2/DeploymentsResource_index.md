## GET `/v2/deployments`

List all running deployments.
A deployment is a change in the service setup.

A deployment is identified by an id, affects a set of applications and is composed of deployment steps.
Every step contains a list of actions with following types:

* StartApplication: starts an application, which is currently not running.
* StopApplication: stops an already running application. 
* ScaleApplication: changes the number of instances of an application
* RestartApplication: upgrades an already deployed application with a new version.
* KillAllOldTasksOf: last step of a restart action.


### Example

**Request:**

```
GET /v2/deployments HTTP/1.1
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
Server: Jetty(8.1.11.v20130520)
Transfer-Encoding: chunked
[
    {
        "affectedApplications": [
            "/test/service/srv1", 
            "/test/db/mongo1", 
            "/test/frontend/app1"
        ], 
        "id": "2e72dbf1-2b2a-4204-b628-e8bd160945dd", 
        "steps": [
            [
                {
                    "action": "RestartApplication", 
                    "application": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "RestartApplication", 
                    "application": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "RestartApplication", 
                    "application": "/test/frontend/app1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "application": "/test/frontend/app1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "application": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "application": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "application": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "application": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "application": "/test/frontend/app1"
                }
            ]
        ], 
        "version": "2014-07-09T11:14:11.477Z"
    }
]
```

