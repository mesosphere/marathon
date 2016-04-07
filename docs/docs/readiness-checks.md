---
title: Readiness Checks
---

# Readiness Checks

Marathon already has the concept of Health Checks which constantly monitor the health of an application. During deployments, you might want to have a temporary monitor that waits for your application to become _ready_. This can be for various reasons like cache-warming, JIT warming, or a migration. For these matters, Marathon provides the concept of Readiness Checks. If you define a Readiness Check for an application

- Marathon will check a task's readiness once the task has turned running
- A task will be considered _ready_ as soon as Marathon receives a status code from the configured endpoint which is contained in the `httpStatusCodesForReady` list.
- A deployment will only finish when all new tasks are ready
- Depending on the application's UpgradeStrategy, old tasks will only be killed if a new task has become ready

Marathon currently allows at most one Readiness Check per application, which defines an endpoint that shall be polled for readiness information, as well as which HTTP status codes should be interpreted as _ready_.

### Readiness Check options

- `name` (Optional. Default: `"readinessCheck"`): The name used to identify this readiness check.
- `protocol` (Optional. Default: `"HTTP"`): Protocol of the requests to be performed. One of HTTP, HTTPS.
- `path` (Optional. Default: `"/"`): Path to endpoint exposed by the task that will provide readiness status. Example: /path/to/health.
- `portName` (Optional. Default: `"http-api"`): Name of the port to query as described in the portDefinitions. Example: http-api.
- `intervalSeconds` (Optional. Default: `30 seconds`): Number of seconds to wait between readiness checks. Defaults to 30 seconds.
- `timeoutSeconds` (Optional. Default: `10 seconds`): Number of seconds after which a health check is considered a failure regardless of the response. Must be smaller than intervalSeconds. Defaults to 10 seconds.
- `httpStatusCodesForReady` (Optional. Default: `[200]`): The HTTP/HTTPS status code to treat as _ready_.
- `preserveLastResponse` (Optional. Default: `false`): If true, the last readiness check response will be preserved and exposed in the API as part of a deployment.

### Example Usage

```json
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
```