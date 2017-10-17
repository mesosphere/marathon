---
title: Task Handling Configuration
---

# Task Handling Configuration

You can configure Marathon's actions on unreachable tasks. The `unreachableStrategy` parameter of your app or pod definition allows you to configure this in two ways: by defining when a new task instance should be launched, and by defining when a task instance should be expunged.

```json
"unreachableStrategy": {
    "inactiveAfterSeconds": "<integer>",
    "expungeAfterSeconds": "<integer>"
}
```

## `unreachableStrategy` Configuration Options

- `inactiveAfterSeconds`: If a task instance is unreachable for longer than this value, it is marked inactive and a new instance will launch. At this point, the unreachable task is not yet expunged. The minimum value for this parameter is 0. The default value is 300 seconds.

- `expungeAfterSeconds`: If an instance is unreachable for longer than this value, it will be expunged. An expunged task will be killed if it ever comes back. Instances are usually marked as unreachable before they are expunged, but that is not required. This parameter must be larger than or equal to `unreachableInactiveAfterSeconds`. The default value is 600 seconds.

You can use `inactiveAfterSeconds` and `expungeAfterSeconds` in conjunction with one another. For example, if you configure `inactiveAfterSeconds = 60` and `expungeAfterSeconds = 120`, a task instance will be expunged if it has been unreachable for more than 120 seconds and a second instance will be started if it has been unreachable for more than 60 seconds.

You can configure `unreachableStrategy` to replace unreachable apps or pods as soon as Marathon is aware of them. To enable this behavior, configure your app or pod as shown below:
```
 unreachableStrategy: {
     "inactiveAfterSeconds": 0,
     "expunceAfterSeconds": 0
 }
 ```

## Mesos configuration

By default, Mesos notifies Marathon of an unreachable task after 75 seconds. Change this duration in Mesos by configuring `agent_ping_timeout` and `max_agent_ping_timeouts`.

## Kill Selection
You call also define a kill selection to declare whether Marathon kills the youngest or oldest tasks first when rescaling or otherwise killing multiple tasks. The default value for this parameter is `YoungestFirst`. You can also specify `OldestFirst`.

Add the `killSelection` parameter to your app definition, or to the `PodSchedulingPolicy` parameter of your pod definition.

```json
{
    "killSelection": "YoungestFirst"
}
```

## Persistent Volumes

The default `unreachableStrategy` for apps with persistent volumes will create new instances with new volumes and delete existing volumes (if possible) after an instance has been unreachable for longer than 7 days and has been expunged by Marathon. **Warning:** Data may be deleted when the existing volumes of an unreachable instance are deleted.
