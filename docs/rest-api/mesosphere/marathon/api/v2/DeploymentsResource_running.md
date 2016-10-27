#### GET `/v2/deployments`

List all running deployments.
A deployment is a change in the service setup.

A deployment is identified by an id, affects a set of applications and is composed of deployment steps.
Every step contains a list of actions with following types:

* StartApplication: starts an application, which is currently not running.
* StopApplication: stops an already running application. 
* ScaleApplication: changes the number of instances of an application and allows to kill specified instances while scaling.
* RestartApplication: upgrades an already deployed application with a new version.
* KillAllOldTasksOf: last step of a restart action.


##### Example

**Request:**

```http
GET /v2/deployments HTTP/1.1
Accept: application/json
Content-Type: application/json; charset=utf-8
Host: localhost:8080
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Transfer-Encoding: chunked

[
    {
        "affectedApps": [
            "/test/service/srv1", 
            "/test/db/mongo1", 
            "/test/frontend/app1"
        ], 
        "currentStep": 2,
        "currentActions": [
          {
              "action": "RestartApplication", 
              "app": "/test/frontend/app1",
              "readinessChecks": [
                  {
                      "lastResponse": {
                          "body": "{}", 
                          "contentType": "application/json", 
                          "status": 500
                      }, 
                      "name": "myReadyCheck", 
                      "ready": false, 
                      "taskId": "test_frontend_app1.c9de6033"
                  }
              ]

          }
        ],
        "totalSteps": 9,
        "id": "2e72dbf1-2b2a-4204-b628-e8bd160945dd", 
        "steps": [
            [
                {
                    "action": "RestartApplication", 
                    "app": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "RestartApplication", 
                    "app": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "RestartApplication", 
                    "app": "/test/frontend/app1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "app": "/test/frontend/app1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "app": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "KillAllOldTasksOf", 
                    "app": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "app": "/test/service/srv1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "app": "/test/db/mongo1"
                }
            ], 
            [
                {
                    "action": "ScaleApplication", 
                    "app": "/test/frontend/app1"
                }
            ]
        ], 
        "version": "2014-07-09T11:14:11.477Z"
    }
]
```


