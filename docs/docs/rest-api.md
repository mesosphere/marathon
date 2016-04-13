---
title: REST API
---

# Marathon REST API

* [Apps](#apps)
  * [POST /v2/apps](#post-v2-apps): Create and start a new app
  * [GET /v2/apps](#get-v2-apps): List all running apps
  * [GET /v2/apps/{appId}](#get-v2-apps-appid): List the app `appId`
  * [GET /v2/apps/{appId}/versions](#get-v2-apps-appid-versions): List the versions of the application with id `appId`.
  * [GET /v2/apps/{appId}/versions/{version}](#get-v2-apps-appid-versions-version): List the configuration of the application with id `appId` at version `version`.
  * [PUT /v2/apps/{appId}](#put-v2-apps-appid): Change config of the app
    `appId`
  * [POST /v2/apps/{appId}/restart](#post-v2-apps-appid-restart): Rolling restart of all tasks of the given app
  * [DELETE /v2/apps/{appId}](#delete-v2-apps-appid): Destroy app `appId`
  * [GET /v2/apps/{appId}/tasks](#get-v2-apps-appid-tasks): List running tasks
    for app `appId`
  * [DELETE /v2/apps/{appId}/tasks](#delete-v2-apps-appid-tasks):
    kill tasks belonging to app `appId`
  * [DELETE /v2/apps/{appId}/tasks/{taskId}](#delete-v2-apps-appid-tasks-taskid):
    Kill the task `taskId` that belongs to the application `appId`
* [Groups](#groups) <span class="label label-default">v0.7.0</span>
  * [GET /v2/groups](#get-v2-groups): List all groups
  * [GET /v2/groups/{groupId}](#get-v2-groups-groupid): List the group with the specified ID
  * [POST /v2/groups](#post-v2-groups): Create and start a new groups
  * [PUT /v2/groups/{groupId}](#put-v2-groups-groupid): Change parameters of a deployed application group
  * [DELETE /v2/groups/{groupId}](#delete-v2-groups-groupid): Destroy a group
* [Tasks](#tasks)
  * [GET /v2/tasks](#get-v2-tasks): List all running tasks
  * [POST /v2/tasks/delete](#post-v2-tasks-delete): Kill given list of tasks
* [Deployments](#deployments) <span class="label label-default">v0.7.0</span>
  * [GET /v2/deployments](#get-v2-deployments): List running deployments
  * [DELETE /v2/deployments/{deploymentId}](#delete-v2-deployments-deploymentid): Revert or cancel the deployment with `deploymentId`
* [Event Stream](#event-stream) <span class="label label-default">v0.9.0</span>
  * [GET /v2/events](#get-v2-events): Attach to the event stream
* [Event Subscriptions](#event-subscriptions)
  * [POST /v2/eventSubscriptions](#post-v2-eventsubscriptions): Register a callback URL as an event subscriber
  * [GET /v2/eventSubscriptions](#get-v2-eventsubscriptions): List all event subscriber callback URLs
  * [DELETE /v2/eventSubscriptions](#delete-v2-eventsubscriptions) Unregister a callback URL from the event subscribers list
* [Queue](#queue) <span class="label label-default">v0.7.0</span>
  * [GET /v2/queue](#get-v2-queue): List content of the staging queue.
  * [DELETE /v2/queue/{appId}/delay](#delete-v2-queue-appid-delay): <span class="label label-default">v0.10.0</span> Reset the application specific task launch delay.
* [Server Info](#server-info) <span class="label label-default">v0.7.0</span>
  * [GET /v2/info](#get-v2-info): Get info about the Marathon Instance
  * [GET /v2/leader](#get-v2-leader): Get the current leader
  * [DELETE /v2/leader](#delete-v2-leader): Causes the current leader to abdicate, triggering a new election
* [Miscellaneous](#miscellaneous)
  * [GET /ping](#get-ping)
  * [GET /logging](#get-logging)
  * [GET /help](#get-help)
  * [GET /metrics](#get-metrics)

### Apps

{% include_relative rest-api/mesosphere/marathon/api/v2/AppsResource_create.md %}

{% include_relative rest-api/mesosphere/marathon/api/v2/AppsResource_index.md %}

{% include_relative rest-api/mesosphere/marathon/api/v2/AppsResource_show.md %}

#### GET `/v2/apps/{appId}/versions`

List the versions of the application with id `appId`.

##### Example

**Request:**

```http
GET /v2/apps/my-app/versions HTTP/1.1
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
    "versions": [
        "2014-04-04T06:25:31.399Z"
    ]
}
```

#### GET `/v2/apps/{appId}/versions/{version}`

List the configuration of the application with id `appId` at version `version`.

##### Example

**Request:**

```http
GET /v2/apps/my-app/versions/2014-03-01T23:17:50.295Z HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
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
    "cmd": "sleep 60",
    "constraints": [],
    "container": null,
    "cpus": 0.1,
    "env": {},
    "executor": "",
    "id": "my-app",
    "instances": 4,
    "mem": 5.0,
    "ports": [
        18027,
        13200
    ],
    "uris": [
        "https://raw.github.com/mesosphere/marathon/master/README.md"
    ],
    "version": "2014-03-01T23:17:50.295Z"
}
```

#### PUT `/v2/apps/{appId}`

Change parameters of a running application.  The new application parameters
apply only to subsequently created tasks.  Currently running tasks are
restarted, while maintaining the `minimumHealthCapacity`

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>force</code></td>
      <td><code>boolean</code></td>
      <td>If the app is affected by a running deployment, then the update
        operation will fail. The current deployment can be overridden by setting
        the `force` query parameter.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example

**Request:**

```http
PUT /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 126
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "cmd": "sleep 55",
    "constraints": [
        [
            "hostname",
            "UNIQUE",
            ""
        ]
    ],
    "cpus": "0.3",
    "instances": "2",
    "mem": "9",
    "ports": [
        9000
    ]
}
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "83b215a6-4e26-4e44-9333-5c385eda6438",
    "version": "2014-08-26T07:37:50.462Z"
}
```

##### Example (version rollback)

If the `version` key is supplied in the JSON body, the rest of the object is ignored.  If the supplied version is known, then the app is updated (a new version is created) with those parameters.  Otherwise, if the supplied version is not known Marathon responds with a 404.

**Request:**

```http
PUT /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 39
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "version": "2014-03-01T23:17:50.295Z"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "83b215a6-4e26-4e44-9333-5c385eda6438",
    "version": "2014-08-26T07:37:50.462Z"
}
```

##### Example (update an app that is locked by a running deployment)

If the app is affected by a currently running deployment, then the
update operation fails.  As indicated by the response message, the current
deployment can be overridden by setting the `force` query parameter in a
subsequent request.

**Request:**

```http
PUT /v2/apps/test HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Length: 18
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.8.0

{
    "instances": "2"
}
```

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deployments": [
        {
            "id": "5cd987cd-85ae-4e70-8df7-f1438367d9cb"
        }
    ],
    "message": "App is locked by one or more deployments. Override with the option '?force=true'. View details at '/v2/deployments/<DEPLOYMENT_ID>'."
}
```

#### POST `/v2/apps/{appId}/restart`

Initiates a rolling restart of all running tasks of the given app. This call respects the configured `minimumHealthCapacity`.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>force</code></td>
      <td><code>boolean</code></td>
      <td>If the app is affected by a running deployment, then the update
        operation will fail. The current deployment can be overridden by setting
        the `force` query parameter.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example

**Request:**

```http
POST /v2/apps/my-app/restart HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "deploymentId": "83b215a6-4e26-4e44-9333-5c385eda6438",
    "version": "2014-08-26T07:37:50.462Z"
}
```

#### DELETE `/v2/apps/{appId}`

Destroy an application. All data about that application will be deleted.

##### Example

**Request:**

```http
DELETE /v2/apps/my-app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```


**Response:**

```http
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, must-revalidate
Content-Type: application/json
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)
Transfer-Encoding: chunked

{
    "deploymentId": "14f48a7d-261e-4641-a158-8c5894c3116a",
    "version": "2015-04-21T10:34:13.646Z"
}
```


#### GET `/v2/apps/{appId}/tasks`

List all running tasks for application `appId`.

##### Example (as JSON)

**Request:**

```http
GET /v2/apps/my-app/tasks HTTP/1.1
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
    "tasks": [
        {
            "host": "agouti.local",
            "id": "my-app_1-1396592790353",
            "ports": [
                31336,
                31337
            ],
            "stagedAt": "2014-04-04T06:26:30.355Z",
            "startedAt": "2014-04-04T06:26:30.860Z",
            "version": "2014-04-04T06:26:23.051Z"
        },
        {
            "host": "agouti.local",
            "id": "my-app_0-1396592784349",
            "ports": [
                31382,
                31383
            ],
            "stagedAt": "2014-04-04T06:26:24.351Z",
            "startedAt": "2014-04-04T06:26:24.919Z",
            "version": "2014-04-04T06:26:23.051Z"
        }
    ]
}
```

##### Example (as text)

**Request:**

```http
GET /v2/apps/my-app/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

my-app  19385 agouti.local:31336  agouti.local:31364  agouti.local:31382 
my-app  11186 agouti.local:31337  agouti.local:31365  agouti.local:31383 
```

#### DELETE `/v2/apps/{appId}/tasks`

Kill tasks that belong to the application `appId`.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>host</code></td>
      <td><code>string</code></td>
      <td>Kill only those tasks running on host <code>host</code>.
        Default: <code>none</code>.</td>
    </tr>
    <tr>
      <td><code>scale</code></td>
      <td><code>boolean</code></td>
      <td>Scale the app down (i.e. decrement its <code>instances</code> setting
        by the number of tasks killed) after killing the specified tasks.
        Default: <code>false</code>.</td>
    </tr>
    <tr>
      <td><code>wipe</code></td>
      <td><code>boolean</code></td>
      <td>If <code>wipe=true</code> is specified and the app uses local persistent volumes, associated dynamic reservations will be unreserved, and persistent volumes will be destroyed. Only possible if <code>scale=false</code> or not specified.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example

**Request:**

```http
DELETE /v2/apps/my-app/tasks?host=mesos.vm&scale=false HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "tasks": []
}
```

#### DELETE `/v2/apps/{appId}/tasks/{taskId}`

Kill the task with ID `taskId` that belongs to the application `appId`.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>scale</code></td>
      <td><code>boolean</code></td>
      <td>Scale the app down (i.e. decrement its <code>instances</code> setting
        by the number of tasks killed) after killing the specified task.
        Only possible if <code>wipe=false</false> or not specified.
        Default: <code>false</code>.</td>
    </tr>
    <tr>
      <td><code>wipe</code></td>
      <td><code>boolean</code></td>
      <td>If <code>wipe=true</code> is specified and the app uses local persistent volumes, associated dynamic reservations will be unreserved, and persistent volumes will be destroyed. Only possible if <code>scale=false</code> or not specified.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example

**Request:**

```http
DELETE /v2/apps/my-app/tasks/my-app_3-1389916890411 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "task": {
        "host": "mesos.vm",
        "id": "my-app_3-1389916890411",
        "ports": [
            31509,
            31510
        ],
        "stagedAt": "2014-01-17T00:01+0000",
        "startedAt": "2014-01-17T00:01+0000"
    }
}
```

### Groups

#### GET `/v2/groups`

List all groups.

**Request:**

```http
GET /v2/groups HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [],
    "dependencies": [],
    "groups": [
        {
            "apps": [
                {
                    "args": null,
                    "backoffFactor": 1.15,
                    "backoffSeconds": 1,
                    "maxLaunchDelaySeconds": 3600,
                    "cmd": "sleep 30",
                    "constraints": [],
                    "container": null,
                    "cpus": 1.0,
                    "dependencies": [],
                    "disk": 0.0,
                    "env": {},
                    "executor": "",
                    "healthChecks": [],
                    "id": "/test/app",
                    "instances": 1,
                    "mem": 128.0,
                    "ports": [
                        10000
                    ],
                    "requirePorts": false,
                    "storeUrls": [],
                    "upgradeStrategy": {
                        "minimumHealthCapacity": 1.0
                    },
                    "uris": [],
                    "user": null,
                    "version": "2014-08-28T01:05:40.586Z"
                }
            ],
            "dependencies": [],
            "groups": [],
            "id": "/test",
            "version": "2014-08-28T01:09:46.212Z"
        }
    ],
    "id": "/",
    "version": "2014-08-28T01:09:46.212Z"
}
```

#### GET `/v2/groups/{groupId}`

List the group with the specified ID.

**Request:**

```http
GET /v2/groups/test HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "apps": [
        {
            "args": null,
            "backoffFactor": 1.15,
            "backoffSeconds": 1,
            "maxLaunchDelaySeconds": 3600,
            "cmd": "sleep 30",
            "constraints": [],
            "container": null,
            "cpus": 1.0,
            "dependencies": [],
            "disk": 0.0,
            "env": {},
            "executor": "",
            "healthChecks": [],
            "id": "/test/app",
            "instances": 1,
            "mem": 128.0,
            "ports": [
                10000
            ],
            "requirePorts": false,
            "storeUrls": [],
            "upgradeStrategy": {
                "minimumHealthCapacity": 1.0
            },
            "uris": [],
            "user": null,
            "version": "2014-08-28T01:05:40.586Z"
        }
    ],
    "dependencies": [],
    "groups": [],
    "id": "/test",
    "version": "2014-08-28T01:09:46.212Z"
}
```

#### POST `/v2/groups`

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
],
"version": "2014-03-01T23:29:30.158Z"
}
```

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a
`group_change_success` and `group_change_failed` event with the given version.

### Example

**Request:**

```http
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

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/v2/groups/product
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```

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
],
"version": "2014-03-01T23:29:30.158Z"
}
```

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a
`group_change_success` and `group_change_failed` event with the given version.


### Example

**Request:**

```http
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


```http
HTTP/1.1 201 Created
Location: http://localhost:8080/v2/groups/product
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```

#### PUT `/v2/groups/{groupId}`

Change parameters of a deployed application group.

* Changes to application parameters will result in a restart of this application.
* A new application added to the group is started.
* An existing application removed from the group gets stopped.

If there are no changes to the application definition, no restart is triggered.
During restart marathon keeps track, that the configured amount of minimal running instances are _always_ available.

A deployment can run forever. This is the case, when the new application has a problem and does not become healthy.
In this case, human interaction is needed with 2 possible choices:

* Rollback to an existing older version (send an existing `version` in the body)
* Update with a newer version of the group which does not have the problems of the old one.

If there is an upgrade process already in progress, a new update will be rejected unless the force flag is set.
With the force flag given, a running upgrade is terminated and a new one is started.

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a
`group_change_success` and `group_change_failed` event with the given version.

### Example

**Request:**

```http
PUT /v2/groups/test/project HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Length: 541
Content-Type: application/json; charset=utf-8
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0

{
    "apps": [
        {
            "cmd": "ruby app2.rb",
            "constraints": [],
            "container": null,
            "cpus": 0.2,
            "env": {},
            "executor": "//cmd",
            "healthChecks": [
                {
                    "initialDelaySeconds": 15,
                    "intervalSeconds": 5,
                    "path": "/health",
                    "portIndex": 0,
                    "protocol": "HTTP",
                    "timeoutSeconds": 15
                }
            ],
            "id": "app",
            "instances": 6,
            "mem": 128.0,
            "ports": [
                19970
            ],
            "uris": []
        }
    ]
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "c0e7434c-df47-4d23-99f1-78bd78662231",
    "version": "2014-08-28T16:45:41.063Z"
}
```

### Example

Scale a group.

The scaling affects apps directly in the group as well as all transitive applications referenced by subgroups of this group.
The scaling factor is applied to each individual instance count of each application.

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a
`group_change_success` and `group_change_failed` event with the given version.

**Request:**

```http
PUT /v2/groups/product/service HTTP/1.1
Content-Length: 123
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{ "scaleBy": 1.5 }
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "c0e7434c-df47-4d23-99f1-78bd78662231",
    "version": "2014-08-28T16:45:41.063Z"
}
```

### Example

Rollback a group.

In case of an erroneous update, a group can be rolled back by sending just a version, that is known to work, to the update
endpoint.

**Request:**

```http
PUT /v2/groups/product/service HTTP/1.1
Content-Length: 123
Host: localhost:8080
User-Agent: HTTPie/0.7.2
{ "version": "2014-08-27T15:34:48.163Z" }
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "c0e7434c-df47-4d23-99f1-78bd78662231",
    "version": "2014-08-28T16:45:41.063Z"
}
```

### Example

Deployment dry run.

Get a preview of the deployment steps Marathon would run for a given group update.

**Request:**

```http
PUT /v2/groups/product?dryRun=true HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Type: application/json
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0

{
    "id": "product",
    "groups": [{
        "id": "service",
        "groups": [{
            "id": "us-east",
            "apps": [
                {
                    "id": "app1",
                    "cmd": "someExecutable"
                },
                {
                    "id": "app2",
                    "cmd": "someOtherExecutable"
                }
            ]
        }],
        "dependencies": ["/product/database", "../backend"]
    }],
    "version": "2014-03-01T23:29:30.158Z"
}
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "steps" : [
        {
            "actions" : [
                {
                    "app" : "app1",
                    "type" : "StartApplication"
                },
                {
                    "app" : "app2",
                    "type" : "StartApplication"
                }
            ]
        },
        {
            "actions" : [
                {
                    "type" : "ScaleApplication",
                    "app" : "app1"
                }
            ]
        },
        {
            "actions" : [
                {
                    "app" : "app2",
                    "type" : "ScaleApplication"
                }
            ]
        }
    ]
}
```

#### DELETE `/v2/groups/{groupId}`

Destroy a group. All data about that group and all associated applications will be deleted.

Since the deployment of the group can take a considerable amount of time, this endpoint returns immediatly with a version.
The failure or success of the action is signalled via event. There is a
`group_change_success` and `group_change_failed` event with the given version.

**Request:**

```http
DELETE /v2/groups/product/service/app HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: curl/7.35.0
```


**Response:**

```http
HTTP/1.1 200 Ok
Content-Type: application/json
Transfer-Encoding: chunked
Server: Jetty(8.y.z-SNAPSHOT)
{"version":"2014-07-01T10:20:50.196Z"}
```

### Tasks

#### GET `/v2/tasks`

List tasks of all applications.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>status</code></td>
      <td><code>string</code></td>
      <td>Return only those tasks whose <code>status</code> matches this
        parameter. If not specified, all tasks are returned. Possible values:
        <code>running</code>, <code>staging</code>. Default: <code>none</code>.</td>
    </tr>
  </tbody>
</table>

##### Example (as JSON)

**Request:**

```http
GET /v2/tasks HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Type: application/json; charset=utf-8
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "appId": "/bridged-webapp",
            "healthCheckResults": [
                {
                    "alive": true,
                    "consecutiveFailures": 0,
                    "firstSuccess": "2014-10-03T22:57:02.246Z",
                    "lastFailure": null,
                    "lastSuccess": "2014-10-03T22:57:41.643Z",
                    "taskId": "bridged-webapp.eb76c51f-4b4a-11e4-ae49-56847afe9799"
                }
            ],
            "host": "10.141.141.10",
            "id": "bridged-webapp.eb76c51f-4b4a-11e4-ae49-56847afe9799",
            "ports": [
                31000
            ],
            "servicePorts": [
                9000
            ],
            "stagedAt": "2014-10-03T22:16:27.811Z",
            "startedAt": "2014-10-03T22:57:41.587Z",
            "version": "2014-10-03T22:16:23.634Z"
        },
        {
            "appId": "/bridged-webapp",
            "healthCheckResults": [
                {
                    "alive": true,
                    "consecutiveFailures": 0,
                    "firstSuccess": "2014-10-03T22:57:02.246Z",
                    "lastFailure": null,
                    "lastSuccess": "2014-10-03T22:57:41.649Z",
                    "taskId": "bridged-webapp.ef0b5d91-4b4a-11e4-ae49-56847afe9799"
                }
            ],
            "host": "10.141.141.10",
            "id": "bridged-webapp.ef0b5d91-4b4a-11e4-ae49-56847afe9799",
            "ports": [
                31001
            ],
            "servicePorts": [
                9000
            ],
            "stagedAt": "2014-10-03T22:16:33.814Z",
            "startedAt": "2014-10-03T22:57:41.593Z",
            "version": "2014-10-03T22:16:23.634Z"
        }
    ]
}

```

##### Example (as text)

In text/plain only tasks with status `running` will be returned.

**Request:**

```http
GET /v2/tasks HTTP/1.1
Accept: text/plain
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: text/plain
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

my-app  19385 agouti.local:31336  agouti.local:31364  agouti.local:31382 
my-app2  11186 agouti.local:31337  agouti.local:31365  agouti.local:31383 
```

#### POST `/v2/tasks/delete`

Kill the given list of tasks and scale apps if requested.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>scale</code></td>
      <td><code>boolean</code></td>
      <td>Scale the app down (i.e. decrement its <code>instances</code> setting
        by the number of tasks killed) after killing the specified tasks. Only possible if <code>wipe=false</code> or not specified.
        Default: <code>false</code>.</td>
    </tr>
    <tr>
      <td><code>wipe</code></td>
      <td><code>boolean</code></td>
      <td>If <code>wipe=true</code> is specified and the app uses local persistent volumes, associated dynamic reservations will be unreserved, and persistent volumes will be destroyed. Only possible if <code>scale=false</code> or not specified.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example (as JSON)

**Request:**

```http
POST /v2/tasks/delete HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Type: application/json; charset=utf-8
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
{
    "ids": [
        "task.25ab260e-b5ec-11e4-a4f4-685b35c8a22e",
        "task.5e7b39d4-b5f0-11e4-8021-685b35c8a22e",
        "task.a21cb64a-b5eb-11e4-a4f4-685b35c8a22e"
    ]
}

```

**Response:**

```http
HTTP/1.1 200 OK
Content-Length: 0
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
```

### Deployments

<span class="label label-default">v0.7.0</span>

{% include_relative rest-api/mesosphere/marathon/api/v2/DeploymentsResource_running.md %}

{% include_relative rest-api/mesosphere/marathon/api/v2/DeploymentsResource_cancel.md %}

### Event Stream

<span class="label label-default">v0.9.0</span>

#### GET `/v2/events`

Attach to the marathon event stream.

To use this endpoint, the client has to accept the text/event-stream content type.
Please note: a request to this endpoint will not be closed by the server.
If an event happens on the server side, this event will be propagated to the client immediately.
See [Server Sent Events](http://www.w3schools.com/html/html5_serversentevents.asp) for a more detailed explanation.

**Request:**

```
GET /v2/events HTTP/1.1
Accept: text/event-stream
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, must-revalidate
Connection: close
Content-Type: text/event-stream;charset=UTF-8
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)

```

If an event happens on the server side, it is sent as plain json prepended with the mandatory `data:` field.

**Response:**
```
data: {"remoteAddress":"96.23.11.158","eventType":"event_stream_attached","timestamp":"2015-04-28T12:14:57.812Z"}

data: {"groupId":"/","version":"2015-04-28T12:24:12.098Z","eventType":"group_change_success","timestamp":"2015-04-28T12:24:12.224Z"}
```

### Event Subscriptions

#### POST /v2/eventSubscriptions

Register a callback URL as an event subscriber.

NOTE: To activate this endpoint, you need to start Marathon with `--event_subscriber http_callback`.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>callbackUrl</code></td>
      <td><code>string</code></td>
      <td>URL to which events should be posted. <strong>Required.</strong></td>
    </tr>
  </tbody>
</table>

**Request:**

```http
POST /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "callbackUrl": "http://localhost:9292/callback",
    "clientIp": "0:0:0:0:0:0:0:1",
    "eventType": "subscribe_event"
}
```

#### GET `/v2/eventSubscriptions`

List all event subscriber callback URLs.

NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`.

##### Example

**Request:**

```http
GET /v2/eventSubscriptions HTTP/1.1
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
    "callbackUrls": [
        "http://localhost:9292/callback"
    ]
}
```

#### DELETE `/v2/eventSubscriptions`

Unregister a callback URL from the event subscribers list.

NOTE: To activate this endpoint, you need to start Marathon with `--event_subscriber http_callback`.

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>callbackUrl</code></td>
      <td><code>string</code></td>
      <td>URL passed when the event subscription was created. <strong>Required.</strong></td>
    </tr>
  </tbody>
</table>

##### Example

**Request:**

```http
DELETE /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "callbackUrl": "http://localhost:9292/callback",
    "clientIp": "0:0:0:0:0:0:0:1",
    "eventType": "unsubscribe_event"
}
```

### Queue

<span class="label label-default">v0.7.0</span>

#### GET `/v2/queue`

Show content of the launch queue.

##### Example

```http
GET /v2/queue HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "queue": [
        {
            "count" : 10,
            "delay": {
              "overdue": "true",
              "timeLeftSeconds": 784
            }
            "app" : {
                "cmd" : "tail -f /dev/null",
                "backoffSeconds" : 1,
                "healthChecks" : [],
                "storeUrls" : [],
                "constraints" : [],
                "env" : {},
                "cpus" : 0.1,
                "labels" : {},
                "instances" : 10,
                "ports" : [
                   10000
                ],
                "requirePorts" : false,
                "uris" : [],
                "container" : null,
                "backoffFactor" : 1.15,
                "args" : null,
                "version" : "2015-02-09T10:49:59.831Z",
                "maxLaunchDelaySeconds" : 3600,
                "upgradeStrategy" : {
                   "minimumHealthCapacity" : 1,
                   "maximumOverCapacity" : 1
                },
                "dependencies" : [],
                "mem" : 16,
                "id" : "/foo",
                "disk" : 0,
                "executor" : "",
                "user" : null
            }
        }
    ]
}
```

#### DELETE `/v2/queue/{appId}/delay`

The application specific task launch delay can be reset by calling this endpoint 

##### Example

```http
DELETE /v2/queue/myapp/delay HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.9.2
```

```http
HTTP/1.1 204 No Content
Cache-Control: no-cache, no-store, must-revalidate
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)
```

### Server Info

<span class="label label-default">v0.7.0</span>

#### GET `/v2/info`

Get info about the Marathon Instance

**Request:**

```http
GET /v2/info HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Length: 872
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)

{
    "frameworkId": "20140730-222531-1863654316-5050-10422-0000",
    "leader": "127.0.0.1:8080",
    "http_config": {
        "assets_path": null,
        "http_port": 8080,
        "https_port": 8443
    },
    "event_subscriber": {
        "type": "http_callback",
        "http_endpoints": [
            "localhost:9999/events"
        ]
    },
    "marathon_config": {
        "checkpoint": false,
        "executor": "//cmd",
        "failover_timeout": 604800,
        "ha": true,
        "hostname": "127.0.0.1",
        "local_port_max": 49151,
        "local_port_min": 32767,
        "master": "zk://localhost:2181/mesos",
        "mesos_leader_ui_url": "http://mesos.vm:5050",
        "mesos_role": null,
        "mesos_user": "root",
        "reconciliation_initial_delay": 30000,
        "reconciliation_interval": 30000,
        "task_launch_timeout": 60000
    },
    "name": "marathon",
    "version": "0.7.0-SNAPSHOT",
    "zookeeper_config": {
        "zk": "zk://localhost:2181/marathon",
        "zk_timeout": 10000,
        "zk_session_timeout": 1800000,
        "zk_max_version": 5
    }
}
```

#### GET `/v2/leader`

Returns the current leader.
If no leader exists, Marathon will respond with a 404 error.

**Request:**

```http
GET /v2/leader HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Length: 872
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)

{
    "leader": "127.0.0.1:8080"
}
```

#### DELETE `/v2/leader`

<span class="label label-default">v0.7.7</span>

Causes the current leader to abdicate, triggering a new election.
If no leader exists, Marathon will respond with a 404 error.

**Request:**

```http
DELETE /v2/leader HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.7.2
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Length: 872
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)

{
    "message": "Leadership abdicated"
}
```

### Miscellaneous

**Request:**

```http
```

**Response:**

```http
```

#### GET `/ping`

**Request:**

```http
GET /ping HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Access-Control-Allow-Credentials: true
Cache-Control: must-revalidate,no-cache,no-store
Content-Length: 5
Content-Type: text/plain;charset=ISO-8859-1
Server: Jetty(8.y.z-SNAPSHOT)

pong
```

#### GET `/logging`

**Request:**

```http
GET /logging HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

_HTML-only endpoint_

#### GET `/help`

**Request:**

```http
GET /help HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

_HTML-only endpoint_

#### GET `/metrics`

**Request:**

```http
GET /metrics HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Cache-Control: must-revalidate,no-cache,no-store
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "counters": {
        ...
    },
    "gauges": {
        ...
    },
    "histograms": {},
    "meters": {
        ...
    },
    "timers": {
        ...
    },
    "version": "3.0.0"
}
```
