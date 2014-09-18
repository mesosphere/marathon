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
  "clientIp": "0:0:0:0:0:0:0:1",
  "uri": "/v1/apps/start",
  "appDefinition":{
    "id": "sleep",
    "cmd": "sleep 10",
    "env": {},
    "instances": 1,
    "cpus": 1.0,
    "mem": 10.0,
    "executor": "",
    "constraints": [],
    "uris": [],
    "ports": [14480],
    "taskRateLimit": 1.0
  }
}
```

### Status Update

Fired every time the status of a task changes:

``` json
{
  "eventType": "status_update_event",
  "taskId": "sleep_0-1389757007517",
  "taskStatus": 2,
  "appID": "sleep",
  "host": "zwerg",
  "ports": [31372]
}
```
