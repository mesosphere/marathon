---
title: Health Checks and Task Termination
---

# Health Checks and Task Termination

Health checks may be specified per application to be run against that
application's tasks.

- The default health check employs Mesos' knowledge of the task state
  `TASK_RUNNING => healthy`.
- Marathon provides a `health` member of the task resource
  via the [REST API]({{ site.baseurl }}/docs/rest-api.html), so you can add a
  health check to your application definition.

A health check is considered passing if (1) its HTTP response code is between
200 and 399 inclusive, and (2) its response is received within the
`timeoutSeconds` period. If a task fails more than `maxConsecutiveFailures`
health checks consecutively, that task is killed.

##### Example usage

HTTP:

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

or Mesos HTTP:

```json
{
  "path": "/api/health",
  "portIndex": 0,
  "protocol": "MESOS_HTTP",
  "gracePeriodSeconds": 300,
  "intervalSeconds": 60,
  "timeoutSeconds": 20,
  "maxConsecutiveFailures": 3
}
```

or secure HTTP:

```json
{
  "path": "/api/health",
  "portIndex": 0,
  "protocol": "HTTPS",
  "gracePeriodSeconds": 300,
  "intervalSeconds": 60,
  "timeoutSeconds": 20,
  "maxConsecutiveFailures": 3,
  "ignoreHttp1xx": false
}
```

*Note:* HTTPS health checks do not verify the SSL certificate.

or TCP:

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

or COMMAND:

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

*Note:* Command health checks in combination with dockerized tasks were
broken in Mesos v0.23.0 and v0.24.0. This issue has been fixed in
v0.23.1 and v0.24.1. See [MESOS-3136](https://issues.apache.org/jira/browse/MESOS-3136) for
more details.

*Note:* If you are using double quotes inside your commands please ensure to escape them.
This is required as Mesos runs the healthcheck command inside via `/bin/sh -c ""`.
See example below and [MESOS-4812](https://issues.apache.org/jira/browse/MESOS-4812) for details

```json
{
  "protocol": "COMMAND",
  "command": { "value": "/bin/bash -c \\\"</dev/tcp/$HOST/$PORT0\\\"" }
}
```

#### Health check options

The first thing you need to decide is the protocol of your health check:

* `protocol` (Optional. Default: "HTTP"): Protocol of the requests to be
  performed. One of `HTTP` / `HTTPS` / `TCP` / `COMMAND` / `MESOS_HTTP` /
  `MESOS_HTTPS` / `MESOS_TCP`.

HTTP, HTTPS and TCP health checks are executed by Marathon and thus test
the reachability from the current Marathon leader. `MESOS_HTTP`,
`MESOS_HTTPS`, `MESOS_TCP` and `COMMAND` health checks are locally
executed by Mesos on the agent running the corresponding task.

**Note:** We strongly recommend Mesos-based health checks over Marathon-based health checks.
Marathon-based health checks are deprecated and will be removed in a future version. See the [1.4.0 release notes](https://github.com/mesosphere/marathon/releases/tag/v1.4.0) for more information.

Options applicable to every protocol:

* `gracePeriodSeconds` (Optional. Default: 300): Health check failures are
  ignored within this number of seconds or until the task becomes healthy for
  the first time.
* `intervalSeconds` (Optional. Default: 60): Number of seconds to wait between
  health checks.
* `maxConsecutiveFailures`(Optional. Default: 3): Number of consecutive health
  check failures after which the unhealthy task should be killed.
  HTTP & TCP health checks: If this value is `0`, tasks will not be killed if
  they fail the health check.
* `timeoutSeconds` (Optional. Default: 20): Number of seconds after which a
  health check is considered a failure regardless of the response.

For `MESOS_HTTP`, `MESOS_HTTPS`, `MESOS_TCP`, `TCP`, `HTTP` and `HTTPS`
health checks, either `port` or `portIndex` may be used. If none is
provided, `portIndex` is assumed. If `port` is provided, it takes
precedence overriding any `portIndex` option.

* `portIndex` (Optional. Default: 0): Index in this app's `ports` or
  `portDefinitions` array to be used for health requests. An index is used
  so the app can use random ports, like `[0, 0, 0]` for example, and tasks
  could be started with port environment variables like `$PORT1`.
* `port` (Optional. Default: None): Port number to be used for health requests.

The following option applies only to `MESOS_HTTP`, `MESOS_HTTPS`, `HTTP`, and
`HTTPS` health checks:

* `path` (Optional. Default: "/"): Path to endpoint exposed by the task that
  will provide health  status. Example: "/path/to/health".

The following options only apply to `HTTP` and `HTTPS` health checks:

* `ignoreHttp1xx` (Optional. Default: false): Ignore HTTP informational status
  codes 100 to 199. If the HTTP health check returns one of these, the result is
  discarded and the health status of the task remains unchanged.

#### Health Lifecycle

The application health lifecycle is represented by the finite state machine in
figure 1 below. In the diagram:

- `i` is the number of requested instances
- `r` is the number of running instances
- `h` is the number of healthy instances

<p class="text-center">
  <img src="{{site.baseurl}}/img/app-state.png" width="481" height="797" alt=""><br>
  <em>Figure 1: The Application Health Lifecycle</em>
</p>

# Task Termination

## TASK_KILLING

TASK_KILLING is a task state that signals that a task has received a kill request and is in the grace period. Other tools, for instance a load balancer or service discovery tool, should not route traffic to tasks in that state.

The `task_killing` feature must be enabled in order to make the state available. See the [Command Line Flags documentation]({{ site.baseurl }}/docs/command-line-flags) for details.

## taskKillGracePeriodSeconds

While health checks allow you to determine when a task is unhealthy and should be terminated, the `taskKillGracePeriodSeconds` field allows you to set the amount of time between when the executor sends the `SIGTERM` message to gracefully terminate a task and when it kills it by sending `SIGKILL`. This field can be useful if you have a task that does not shut down immediately. If you do not set the grace period duration, the default is 3 seconds.

## Example

The following long-running service needs a grace period of at least 6 seconds:

```bash
#!/bin/bash

# A signal handler to shut down cleanly.
# Shutdown takes at least 6 seconds! The grace period should be set higher than this.
function terminate {
  echo $(date) Marathon requested shutdown, stopping the DB
  sleep 3
  echo $(date) Cleaning up disk
  sleep 3
  echo $(date) All done, exiting cleanly
  exit 0
}

# catch TERM signals
trap terminate SIGTERM

echo $(date) Long-running service running, pid $$
while true; do
  sleep 1
done
```

To set the necessary grace period, add the `taskKillGracePeriodSeconds` field to your application definition:

```json
{
  "id": "/foo",
  "instances": 2,
  "cmd": "sleep 1000",
  "cpus": 0.1,
  "disk": 0,
  "mem": 16,
  "labels": {
    "owner": "zeus",
    "note": "Away from olympus"
  },
  "taskKillGracePeriodSeconds": 30
}
```
