#### GET `/v2/apps`

List all running applications.

The list of running applications can be filtered by application identifier, command and label selector.
All filters can be applied at the same time.

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
      <td><code>cmd</code></td>
      <td><code>string</code></td>
      <td>
        Filter apps to only those whose commands contain <code>cmd</code>.
        Default: <code>""</code>.
      </td>
    </tr>
    <tr>
      <td><code>embed</code></td>
      <td><code>string</code></td>
      <td>
        Embeds nested resources that match the supplied path.
        You can specify this parameter multiple times with different values, e.g.
        <code>"embed=apps.deployments&embed=apps.lastTaskFailure"</code>
        Default: <code>apps.counts</code>. You are encouraged to specify
        <code>"embed=apps.counts"</code>
        explicitly if you rely on this information because we will change the default
        in a future release. Possible values:
        <ul>
          <li>
            <code>"apps.tasks"</code>. Apps' tasks are not embedded in the response
            by default. This currently implies "apps.deployments" currently but that
            will change in a future version.
          </li>
          <li>
            <code>"apps.counts"</code>. Apps' task counts (tasksStaged, tasksRunning,
            tasksHealthy, tasksUnhealthy) are currently embedded by default but this
            will change in a future release.
          </li>
          <li>
            <code>"apps.deployments"</code>. This embeds the IDs of deployments related to this
            app into "deployments".
          </li>
          <li>
            <code>"apps.lastTaskFailure"</code>. This embeds the "lastTaskFailure" for
            every app if there is one.
          </li>
          <li>
            <code>"apps.failures"</code>. <strong>deprecated</strong> Apps' last failures are not embedded in
            the response by default. This implies "apps.lastTaskFailure", "apps.tasks", "apps.counts" and
            "apps.deployments".
          </li>
          <li>
            <code>"apps.taskStats"</code>. <span class="label label-default">v0.11</span> This exposes some task
            statatistics in the JSON.
          </li>
        </ul>
      </td>
    </tr>
  </tbody>
</table>

##### taskRunning (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=apps.counts" as a query parameter.

The number of tasks running for this application definition.

##### tasksStaged (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=apps.counts" as a query parameter.

The number of tasks staged to run.

##### tasksHealthy (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=apps.counts" as a query parameter.

The number of tasks which are healthy.

##### tasksUnhealthy (Integer)

**Warning** Future versions of Marathon will only deliver this information
when you specify "embed=apps.counts" as a query parameter.

The number of tasks which are unhealthy.

##### deployments (Array of Objects)

Use the query parameter "embed=apps.deployments" to embed this information.

A list of currently running deployments that affect this application.
If this array is nonempty, then this app is locked for updates.

The objects in the list only contain the id of the deployment, e.g.:

```javascript
{
    "id": "44c4ed48-ee53-4e0f-82dc-4df8b2a69057"
}
```

##### taskStats (Object) <span class="label label-default">v0.11</span>

Provides task statistics. The "taskStats" object only gets embedded into the app JSON if 
you pass an `embed=apps.taskStats` query argument.
 
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

##### lastTaskFailure (Object)

Information about the last task failure for debugging purposes.

This information only gets embedded if you specify the "embed=apps.lastTaskFailure" query
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
GET /v2/apps HTTP/1.1
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 0, 
            "tasksStaged": 1, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

#### GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.
Note: the specified command must be contained in the applications command (substring). 

##### Example

**Request:**

```http
GET /v2/apps?cmd=sleep%2060 HTTP/1.1
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

#### GET `/v2/apps?id={identifier}`

List all running applications, filtered by `id`.
Note: the specified id must be contained in the applications id (substring). 

##### Example

**Request:**

```http
GET /v2/apps?id=my HTTP/1.1
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

#### GET `/v2/apps?label={labelSelectorQuery}`

List all running applications, filtered by `labelSelectorQuery`.
This filter selects applications by application labels.


##### Label Selector Query

A label selector query contains one or more label selectors, which are comma separated.
Marathon supports three types of selectors: existence-based, equality-based and set-based. 
In the case of multiple selectors, all must be satisfied so comma separator acts as an AND logical operator.
Labels and values must consist of alphanumeric characters plus `-` `_` and `.`: `-A-Za-z0-9_.`. 
Any other character is possible, but must be escaped with a backslash character.

Example:
`environment==production, tier!=frontend\ tier, deployed in (us, eu), deployed notin (aa, bb)`  

##### Existence based Selector Query

Matches the existence of a label.

- `label`

Example:

- environment


##### Equality based Selector Query

Matches existence of labels and the (non) equality of the value.

- `label` == `value`
- `label` != `value`

Example:

- environment = production
- tier != frontend

##### Set based Selector Query  

Matches existence of labels and the (non) existence of the value in a given set.

- `label` in ( `value`, `value`, ... , `value` )
- `label` notin ( `value`, `value`, ... , `value` )

Example:

- environment in (production, qa)
- tier notin (frontend, backend)



##### Example

**Request:**

```http
GET /v2/apps?label=foo%3d%3done%2c+bla!%3done%2c+foo+in+(one%2c+two%2c+three)%2c+bla+notin+(one%2c+two%2c+three)%2c+existence%22 HTTP/1.1
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "labels": {
                "foo": "one", 
                "bla": "four", 
                "existence": "yes"
            }, 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

#### GET `/v2/apps/product/us-east/*`

List all running applications, which live in the namespace /product/us-east

##### Example

**Request:**

```http
GET /v2/apps/product/us-east/* HTTP/1.1
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```
