---
title: Health Checks
---

# Health Checks

Health checks may be specified per application to be run against that application's tasks.

- The default health check defers to Mesos' knowledge of the task state `TASK_RUNNING => healthy`
- Marathon provides a `health` member of the task resource
  via the [REST API]({{ site.baseurl }}/docs/rest-api.html).

A health check is considered passing if (1) its HTTP response code is between
200 and 399, inclusive, and (2) its response is received within the
`timeoutSeconds` period. If a task fails more than `maxConsecutiveFailures` health
checks consecutively, that task is killed.

##### Example usage

```json
{
  "path": "/api/health",
  "portIndex": 0,
  "protocol": "HTTP",
  "gracePeriodSeconds": 300,
  "intervalSeconds": 60,
  "timeoutSeconds": 20,
  "maxConsecutiveFailures": 3,
  "ignoreHttp1xx": false
}
```

OR

```json
{
  "portIndex": 0,
  "protocol": "TCP",
  "gracePeriodSeconds": 300,
  "intervalSeconds": 60,
  "timeoutSeconds": 20,
  "maxConsecutiveFailures": 0
}
```

OR

*Note:* Command health checks in combination with dockerized tasks were
broken in Mesos v0.23.0 and v0.24.0. This issue has been fixed in
v0.23.1 and v0.24.1.

See [MESOS-3136](https://issues.apache.org/jira/browse/MESOS-3136) for
more details.

```json
{
  "protocol": "COMMAND",
  "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" },
  "gracePeriodSeconds": 300,
  "intervalSeconds": 60,
  "timeoutSeconds": 20,
  "maxConsecutiveFailures": 3
}
```

#### Health check options

The first thing you need to decide is the protocol of your health check:

* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP"/"TCP"/"COMMAND".

HTTP/TCP health checks are executed by Marathon and thus test the reachability from
the current Marathon leader. COMMAND health checks are locally executed by Mesos on
the agent running the corresponding task.

Options applicable to every protocol:

* `gracePeriodSeconds` (Optional. Default: 300): Health check failures are
  ignored within this number of seconds or until the task becomes healthy for
  the first time.
* `intervalSeconds` (Optional. Default: 60): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3): Number of consecutive health
  check failures after which the unhealthy task should be killed. If this value
  is `0`, then tasks will not be killed due to failing this check.
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

For TCP/HTTP health checks, either `port` or `portIndex` may be used. If none is provided, `portIndex` is assumed. If `port` is provided, it takes precedence overriding any `portIndex` option.

* `portIndex` (Optional. Default: 0): Index in this app's `ports` or
  `portDefinitions` array to be used for health requests. An index is used
  so the app can use random ports, like `[0, 0, 0]` for example, and tasks
  could be started with port environment variables like `$PORT1`.
* `port` (Optional. Default: None): Port number to be used for health requests.

The following options only apply to HTTP health checks:

* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".
* `ignoreHttp1xx` (Optional. Default: false): Ignore HTTP informational status
  codes 100 to 199. If the HTTP health check returns one of these, the result is
  discarded and the health status of the task remains unchanged.

#### Health Lifecycle

The application health lifecycle is represented by the finite state machine in figure 1 below.  In the diagram:

- `i` is the number of requested instances
- `r` is the number of running instances
- `h` is the number of healthy instances

<p class="text-center">
  <img src="{{site.baseurl}}/img/app-state.png" width="481" height="797" alt=""><br>
  <em>Figure 1: The Application Health Lifecycle</em>
</p>
