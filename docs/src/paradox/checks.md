---
title: Checks
---

# Checks

Marathon already has the concept of [health checks]({{ site.baseurl }}/docs/health-checks.html) and [readiness checks]({{ site.baseurl }}/docs/readiness-checks.html), which either monitor the health of an application or provide an indication that the service is ready.  Both of these features in Marathon will indicate that the app/pod is "in deployment".

Checks provide an abstract way to check on a task in Mesos providing values back via tasks and podstatus which are *not* interpreted by Marathon.   They are intended to be consumed by services external to Marathon which will take appropriate action on them.   You can think of them as a health check without a kill policy.  The most significant aspect of them is that Marathon does not evaluate anything about the return values of a check.

Checks can be defined as:

* HTTP Check
* TCP Check
* Command Check

When a check is configured for an app/pod, it has zero impact on its deployment status in Marathon.  To control this behavior you will need to use a health check or readiness check.  The value in the task varies depending on check type:

* HTTP Check returns http response status code `"checkResult":{"http":{"statusCode":200}}`
* TCP Check returns boolean true or false `"checkResult":{"tcp":{"succeeded":true}}`
* Command Check returns the command process exit code `checkResult":{"command":{"exitCode":0}}`


### Check options

- `http` (Optional. ): HTTP Check object (see below).  You can only have one check type (http, tcp, or exec).
- `tcp` (Optional. ): TCP Check object (see below). You can only have one check type (http, tcp, or exec).
- `exec` (Optional. ): Command Check object (see below). You can only have one check type (http, tcp, or exec).
- `intervalSeconds` (Optional. Default: `60 seconds`): Number of seconds to wait between checks.
- `timeoutSeconds` (Optional. Default: `20 seconds`): Number of seconds after which a check times out, regardless of the response. This value must be smaller than `intervalSeconds`.
- `delaySeconds` (Optional. Default: `15`): Amount of time to wait until starting the checks.

#### HTTP Check options

- `portIndex` (Optional. ):  The index of the port defined.  The value 0 will be `$PORT0` in the application. You can use either `port` *OR* `portIndex`, but not both.
- `port` (Optional. ): Specific port to check.  This is useful when using a network overlay or have a fixed port.  You can use either `port` *OR* `portIndex`, but not both.
- `path` (Optional. Default: `"/"`): Path to the endpoint the task exposes to provide check status. Example: `/path/to/check`.
- `scheme` (Optional. Default: `"HTTP"`): Protocol of the requests to be performed. Only HTTP is currently supported.

#### TCP Check options

- `portIndex` (Optional. ):  The index of the port defined.  The value 0 will be `$PORT0` in the application. You can either use `port` *OR* `portIndex`, but not both.
- `port` (Optional. ): Specific port to check.  This is useful when using a network overlay or have a fixed port.  You can either use `port` *OR* `portIndex`, but not both.

#### Command Check options

- `shell` (Optional. ):  Object of the command to execute.

### Example HTTP Check Usage

```json
"check":
{
  "id": "http-index-test",
  "cmd": "python3 -m http.server $PORT0",
  "cpus": 1,
  "mem": 128,
  "disk": 0,
  "instances": 1,
  "portDefinitions": [
    {
      "port": 0,
      "protocol": "tcp",
      "name": "http"
    }],
  "requirePorts" : false,
  "env": {},
  "labels": {},
  "check": {
    "http" : {
      "portIndex": 0,
      "path": "/"
    },
    "intervalSeconds": 60,
    "timeoutSeconds": 20
  }
}
```

### Example Command Check Usage

```json
"check":
{
  "id": "sarah-command-check",
  "cmd": "sleep inf",
  "cpus": 1,
  "mem": 128,
  "disk": 0,
  "instances": 1,
  "check": {
    "exec": {
      "command": {
        "shell": "ls"
      }
    },
    "intervalSeconds": 60,
    "timeoutSeconds": 20
  }
}
```
