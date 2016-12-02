---
Configuring Task Handling
---

You can configure Marathon's actions on unreachable tasks. The `unreachableStrategy` parameter of your app or pod definition allows you to configure this in three ways: by defining when a new task instance should be launched, by defining when a task instance should be expunged, and by declaring which task instance should be killed first when rescaling or otherwise killing multiple tasks.

```
"unreachableStrategy": {
	"unreachableInactiveAfterSeconds": <integer>,
	"timeUntilExpungeSeconds": <integer>,
}
```

- `unreachableInactiveAfterSeconds`: If a task instance is unreachable for longer than this value, it is marked inactive and a new instance will launch. At this point, the unreachable task is not yet expunged. The minimum value for this parameter is 1. The default value is 300 seconds.

- `unreachableInactiveAfterSeconds`: If an instance is unreachable for longer than this value, it will be expunged. An expunged task will be killed if it ever comes back. Instances are usually marked as unreachable before they are expunged, but that is not required. This parameter must be larger than `unreachableInactiveAfterSeconds`. The default value is 600 seconds.

You can use `unreachableInactiveAfter` and `unreachableExpungeAfter` in conjunction with one another. For example, if you configure `unreachableInactiveAfter = 60` and `unreachableExpungeAfter = 120`, a task instance will be expunged if it has been unreachable for more than 120 seconds and a second instance will be started if it has been unreachable for more than 60 seconds.