---
title: Readiness Checks
---

# Readiness Checks

Marathon already has the concept of [health checks]({{ site.baseurl }}/docs/health-checks.html), which periodically monitor the health of an application. 

During deployments and runtime configuration updates, however, you might want a temporary monitor that waits for your application to be _ready_. A temporary monitor can be useful for cache-warming, JIT warming, or a migration. Marathon offers a readiness check for these situations.

When you define a readiness check for an application:

- Marathon checks a task's readiness once the task has started running.
- A task is considered _ready_ as soon as Marathon receives a status code from the configured endpoint from the `httpStatusCodesForReady` list.
- A deployment will only finish when all new tasks are ready.
- Depending on the application's [upgradeStrategy]({{ site.baseurl }}/docs/rest-api.html#upgrade-strategy), old tasks will only be killed if a new task has become ready.

Marathon currently allows one readiness check per application. The readiness check defines an endpoint to be polled for readiness information, as well as which HTTP status codes should be interpreted as _ready_.

### Readiness Check options

- `name` (Optional. Default: `"readinessCheck"`): The name used to identify this readiness check.
- `protocol` (Optional. Default: `"HTTP"`): Protocol of the requests to be performed. Either HTTP or HTTPS.
- `path` (Optional. Default: `"/"`): Path to the endpoint the task exposes to provide readiness status. Example: `/path/to/readiness`.
- `portName` Default: `"http-api"`: Name of the port in the portDefinitions section. This port will be used to check readiness. Example: `http-api`.
- `intervalSeconds` (Optional. Default: `30 seconds`): Number of seconds to wait between readiness checks.
- `timeoutSeconds` (Optional. Default: `10 seconds`): Number of seconds after which a readiness check times out, regardless of the response. This value must be smaller than `intervalSeconds`.
- `httpStatusCodesForReady` (Optional. Default: `[200]`): The HTTP/HTTPS status code to treat as _ready_.
- `preserveLastResponse` (Optional. Default: `false`): If true, the last readiness check response will be preserved and exposed in the API as part of a deployment.

### Example Usage

```json
"readinessChecks": [
  {
    "name": "readinessCheck",
    "protocol": "HTTP",
    "path": "/",
    "portName": "http-api",
    "intervalSeconds": 30,
    "timeoutSeconds": 10,
    "httpStatusCodesForReady": [ 200 ],
    "preserveLastResponse": false
  }
]
```
