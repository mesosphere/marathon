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
`timeoutSeconds` period. If a task fails more than `maxConseutiveFailures`
health checks consecutively, that task is killed.

##### Example usage

```json
{
  "path": "/api/health",
  "portIndex": 0,
  "protocol": "HTTP",
  "gracePeriodSeconds": 30,
  "intervalSeconds": 30,
  "timeoutSeconds": 30,
  "maxConsecutiveFailures": 3
}
```

OR

```json
{
  "portIndex": 0,
  "protocol": "TCP",
  "gracePeriodSeconds": 30,
  "intervalSeconds": 30,
  "timeoutSeconds": 30,
  "maxConsecutiveFailures": 0
}
```

OR

```json
{
  "protocol": "COMMAND",
  "command": { "value": "curl -f -X GET http://$HOST:$PORT0/health" },
  "gracePeriodSeconds": 30,
  "intervalSeconds": 30,
  "timeoutSeconds": 30,
  "maxConsecutiveFailures": 3
}
```

#### Health check options

* `gracePeriodSeconds` (Optional. Default: 15): Health check failures are
  ignored within this number of seconds or until the task becomes healthy for
  the first time.
* `intervalSeconds` (Optional. Default: 10): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3) : Number of consecutive health
  check failures after which the unhealthy task should be killed. If this value
  is `0`, then tasks will not be killed due to failing this check.
* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".
  _Note: only used if `protocol == "HTTP"`._
* `portIndex` (Optional. Default: 0): Index in this app's `ports` array to be
  used for health requests. An index is used so the app can use random ports,
  like "[0, 0, 0]" for example, and tasks could be started with port environment
  variables like `$PORT1`.
* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of "HTTP" or "TCP".
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

#### Health Lifecycle

The application health lifecycle is represented by the finite state machine in figure 1 below.  In the diagram:

- `i` is the number of requested instances
- `r` is the number of running instances
- `h` is the number of healthy instances

<p class="text-center">
  <img src="{{site.baseurl}}/img/app-state.png" width="481" height="797" alt=""><br>
  <em>Figure 1: The Application Health Lifecycle</em>
</p>
