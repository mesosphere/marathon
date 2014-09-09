---
title: Event Bus
---

# Event Bus

Marathon has an internal event bus that captures all API requests and scaling events. This is useful for integrating with load balancers, keeping stats, etc.
Events can be subscribed to by pluggable subscribers. Currently an HTTP callback subscriber is implemented that POSTs events in JSON format to one or more endpoints. Other subscribers are easy to add. See the code in
[marathon/event/http](https://github.com/mesosphere/marathon/tree/master/src/main/scala/mesosphere/marathon/event/http)
for guidance.

## Configuration

Add these command line options to configure events:

``` bash
$ ./bin/start --master ... --event_subscriber http_callback --http_endpoints http://host1/foo,http://host2/bar
```

Both host1 and host2 will receive events.

## Event Types

Below are example JSON bodies that are posted by Marathon.

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
  "clientIp": 1.2.3.4,
  "callbackUrl": http://subscriber.acme.org/callbacks
}
```

``` json
{
  "eventType": "unsubscribe_event",
  "timestamp": "2014-03-01T23:29:30.158Z",
  "clientIp": 1.2.3.4,
  "callbackUrl": http://subscriber.acme.org/callbacks
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
  "taskId": "my-app_0-1396592784349",
  "version": "2014-04-04T06:26:23.051Z",
  "alive": true
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
    "action": "ScaleApplication",
    "app": "/my-app"
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
    "action": "ScaleApplication",
    "app": "/my-app"
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
    "action": "ScaleApplication",
    "app": "/my-app"
  }
}
```
