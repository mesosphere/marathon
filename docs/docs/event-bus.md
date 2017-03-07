---
title: Event Bus
---

# Event Bus

Marathon has an internal event bus that captures all API requests and scaling events.
By subscribing to the event bus, you can be informed about every event instantly, without pulling.
The event bus is useful for integrating with any entity that acts based on the state of Marathon, like load balancers, or to compile statistics.

Events can be subscribed to by pluggable subscribers.

The event bus has two APIs:

* The event stream. For more information, see the `/v2/events` entry in the [Marathon REST API Reference](https://mesosphere.github.io/marathon/docs/generated/api.html).

* <span class="label label-default">Deprecated</span> The callback endpoint, which POSTs events in JSON format to one or more endpoints.

The HTTP callback endpoint support is deprecated with Marathon 1.4 and will be removed in an upcoming version.
Please use the event stream instead of the callback endpoint because:

* It is easier to set up.

* Delivery is faster because there is no request/response cycle.

* The events are transferred in order.

Other subscribers are easy to add. See the code in
[marathon/event/http](https://github.com/mesosphere/marathon/tree/master/src/main/scala/mesosphere/marathon/event/http)
for guidance.

## Subscription to Events via The Event Stream

This functionality is always enabled. Marathon implements
[Server-Sent-Events (SSE)](https://en.wikipedia.org/wiki/Server-sent_events) standard.
Events are published on `/v2/events` endpoint.
Any SSE-compatible client can subscribe.
Example subscription using `curl`:

```bash
$ curl -H "Accept: text/event-stream"  <MARATHON_HOST>:<MARATHON_PORT>/v2/events

event: event_stream_attached
data: {"remoteAddress":"127.0.0.1","eventType":"event_stream_attached","timestamp":"2017-02-18T19:12:00.102Z"}
```

### Filtering the Event Stream

Starting from version [1.3.7](https://github.com/mesosphere/marathon/releases/tag/v1.3.7),
Marathon supports filtering the event stream by event type.
To filter by event type,
specify a value for the `event_type` parameter in your `/v2/events` request.
This could be done by adding interesting event type as value for `event_type`
parameter to `/v2/events` request.

The following example only subscribes to events involving a new client
attaching to or detaching from the event stream.

```bash
curl -H "Accept: text/event-stream"  <MARATHON_HOST>:<MARATHON_PORT>/v2/events?event_type=event_stream_detached\&event_type=event_stream_attached
```

## Subscription to Events via the Callback Endpoint

Add these command line options to configure events:

``` bash
$ ./bin/start --master ... --event_subscriber http_callback --http_endpoints http://host1/foo,http://host2/bar
```

Both host1 and host2 will receive events.

## Event Types

Below are example JSON bodies that are send by Marathon.

### API Request

Fired every time Marathon receives an API request that modifies an app (create, update, delete):

``` json
{
  "eventType": "api_post_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "clientIp": "0:0:0:0:0:0:0:1",
  "uri": "/v2/apps/my-app",
  "appDefinition": {
    "args": [],
    "backoffFactor": 1.15,
    "backoffSeconds": 1,
    "cmd": "sleep 30",
    "constraints": [],
    "container": null,
    "cpus": 0.2,
    "dependencies": [],
    "disk": 0.0,
    "env": {},
    "executor": "",
    "healthChecks": [],
    "id": "/my-app",
    "instances": 2,
    "mem": 32.0,
    "ports": [10001],
    "requirePorts": false,
    "storeUrls": [],
    "upgradeStrategy": {
        "minimumHealthCapacity": 1.0
    },
    "uris": [],
    "user": null,
    "version": "2014-09-09T05:57:50.866Z"
  }
}
```

### Status Update

Fired every time the status of a task changes:

``` json
{
  "eventType": "status_update_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "slaveId": "20140909-054127-177048842-5050-1494-0",
  "taskId": "my-app_0-1396592784349",
  "taskStatus": "TASK_RUNNING",
  "appId": "/my-app",
  "host": "slave-1234.acme.org",
  "ports": [31372],
  "version": "2014-04-04T06:26:23.051Z"
}
```

The possible values for `taskStatus` are:

- `TASK_STAGING`
- `TASK_STARTING`
- `TASK_RUNNING`
- `TASK_FINISHED`
- `TASK_FAILED`
- `TASK_KILLING` (only when the `task_killing` feature is enabled)
- `TASK_KILLED`
- `TASK_LOST`

where the last four states are terminal.

### Framework Message

``` json
{
  "eventType": "framework_message_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "slaveId": "20140909-054127-177048842-5050-1494-0",
  "executorId": "my-app.3f80d17a-37e6-11e4-b05e-56847afe9799",
  "message": "aGVsbG8gd29ybGQh"
}
```

### Event Subscription

Fired when a new http callback subscriber is added or removed:

``` json
{
  "eventType": "subscribe_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "clientIp": "1.2.3.4",
  "callbackUrl": "http://subscriber.acme.org/callbacks"
}
```

``` json
{
  "eventType": "unsubscribe_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "clientIp": "1.2.3.4",
  "callbackUrl": "http://subscriber.acme.org/callbacks"
}
```

### Health Checks

``` json
{
  "eventType": "add_health_check_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "appId": "/my-app",
  "healthCheck": {
    "protocol": "HTTP",
    "path": "/health",
    "portIndex": 0,
    "gracePeriodSeconds": 5,
    "intervalSeconds": 10,
    "timeoutSeconds": 10,
    "maxConsecutiveFailures": 3
  }
}
```

``` json
{
  "eventType": "remove_health_check_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "appId": "/my-app",
  "healthCheck": {
    "protocol": "HTTP",
    "path": "/health",
    "portIndex": 0,
    "gracePeriodSeconds": 5,
    "intervalSeconds": 10,
    "timeoutSeconds": 10,
    "maxConsecutiveFailures": 3
  }
}
```

``` json
{
  "eventType": "failed_health_check_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "appId": "/my-app",
  "taskId": "my-app_0-1396592784349",
  "healthCheck": {
    "protocol": "HTTP",
    "path": "/health",
    "portIndex": 0,
    "gracePeriodSeconds": 5,
    "intervalSeconds": 10,
    "timeoutSeconds": 10,
    "maxConsecutiveFailures": 3
  }
}
```

``` json
{
  "eventType": "health_status_changed_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "appId": "/my-app",
  "instanceId": "my-app.instance-c7c311a4-b669-11e6-a48f-0ea4f4b1778c",
  "version": "2014-04-04T06:26:23.051Z",
  "alive": true
}
```

``` json
{
  "appId": "/my-app",
  "taskId": "my-app_0-1396592784349",
  "version": "2016-03-16T13:05:00.590Z",
  "reason": "500 Internal Server Error",
  "host": "localhost",
  "slaveId": "4fb620fa-ba8d-4eb0-8ae3-f2912aaf015c-S0",
  "eventType": "unhealthy_task_kill_event",
  "timestamp": "2016-03-21T09:15:10.764Z"
}
```

### Deployments

```json
{
  "eventType": "group_change_success",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "groupId": "/product-a/backend",
  "version": "2014-04-04T06:26:23.051Z"
}
```

```json
{
  "eventType": "group_change_failed",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "groupId": "/product-a/backend",
  "version": "2014-04-04T06:26:23.051Z",
  "reason": ""
}
```

```json
{
  "eventType": "deployment_success",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "id": "867ed450-f6a8-4d33-9b0e-e11c5513990b"
}
```

```json
{
  "eventType": "deployment_failed",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "id": "867ed450-f6a8-4d33-9b0e-e11c5513990b"
}
```

```json
{
  "eventType": "deployment_info",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "plan": {
    "id": "867ed450-f6a8-4d33-9b0e-e11c5513990b",
    "original": {
      "apps": [],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T06:30:49.667Z"
    },
    "target": {
      "apps": [
        {
          "args": [],
          "backoffFactor": 1.15,
          "backoffSeconds": 1,
          "cmd": "sleep 30",
          "constraints": [],
          "container": null,
          "cpus": 0.2,
          "dependencies": [],
          "disk": 0.0,
          "env": {},
          "executor": "",
          "healthChecks": [],
          "id": "/my-app",
          "instances": 2,
          "mem": 32.0,
          "ports": [10001],
          "requirePorts": false,
          "storeUrls": [],
          "upgradeStrategy": {
              "minimumHealthCapacity": 1.0
          },
          "uris": [],
          "user": null,
          "version": "2014-09-09T05:57:50.866Z"
        }
      ],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T05:57:50.866Z"
    },
    "steps": [
      {
        "action": "ScaleApplication",
        "app": "/my-app"
      }
    ],
    "version": "2014-03-01T23:24:14.846Z"
  },
  "currentStep": {
    "actions": [
      {
        "type": "ScaleApplication",
        "app": "/my-app"
      }
    ]
  }
}
```

```json
{
  "eventType": "deployment_step_success",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "plan": {
    "id": "867ed450-f6a8-4d33-9b0e-e11c5513990b",
    "original": {
      "apps": [],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T06:30:49.667Z"
    },
    "target": {
      "apps": [
        {
          "args": [],
          "backoffFactor": 1.15,
          "backoffSeconds": 1,
          "cmd": "sleep 30",
          "constraints": [],
          "container": null,
          "cpus": 0.2,
          "dependencies": [],
          "disk": 0.0,
          "env": {},
          "executor": "",
          "healthChecks": [],
          "id": "/my-app",
          "instances": 2,
          "mem": 32.0,
          "ports": [10001],
          "requirePorts": false,
          "storeUrls": [],
          "upgradeStrategy": {
              "minimumHealthCapacity": 1.0
          },
          "uris": [],
          "user": null,
          "version": "2014-09-09T05:57:50.866Z"
        }
      ],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T05:57:50.866Z"
    },
    "steps": [
      {
        "action": "ScaleApplication",
        "app": "/my-app"
      }
    ],
    "version": "2014-03-01T23:24:14.846Z"
  },
  "currentStep": {
    "actions": [
      {
        "type": "ScaleApplication",
        "app": "/my-app"
      }
    ]
  }
}
```

```json
{
  "eventType": "deployment_step_failure",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "plan": {
    "id": "867ed450-f6a8-4d33-9b0e-e11c5513990b",
    "original": {
      "apps": [],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T06:30:49.667Z"
    },
    "target": {
      "apps": [
        {
          "args": [],
          "backoffFactor": 1.15,
          "backoffSeconds": 1,
          "cmd": "sleep 30",
          "constraints": [],
          "container": null,
          "cpus": 0.2,
          "dependencies": [],
          "disk": 0.0,
          "env": {},
          "executor": "",
          "healthChecks": [],
          "id": "/my-app",
          "instances": 2,
          "mem": 32.0,
          "ports": [10001],
          "requirePorts": false,
          "storeUrls": [],
          "upgradeStrategy": {
              "minimumHealthCapacity": 1.0
          },
          "uris": [],
          "user": null,
          "version": "2014-09-09T05:57:50.866Z"
        }
      ],
      "dependencies": [],
      "groups": [],
      "id": "/",
      "version": "2014-09-09T05:57:50.866Z"
    },
    "steps": [
      {
        "action": "ScaleApplication",
        "app": "/my-app"
      }
    ],
    "version": "2014-03-01T23:24:14.846Z"
  },
  "currentStep": {
    "actions": [
      {
        "type": "ScaleApplication",
        "app": "/my-app"
      }
    ]
  }
}
```
