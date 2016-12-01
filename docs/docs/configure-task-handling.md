---
Configuring Task Handling
---

You can configure Marathon's actions on unreachable tasks. The `unreachableStrategy` parameter of your app definition allows you to configure this in two ways: by defining when a new task instance should be launched, and by defining when a task instance should be expunged.

```
"unreachableStrategy": {
	"timeUntilInactiveSeconds": <integer>,
	"timeUntilExpungeSeconds": <integer>
}
```

- `timeUntilInactiveSeconds`: If a task instance is unreachable for longer than this value, it is marked inactive and a new instance will launch. At this point, the unreachable task is not yet expunged. The minimum value for this parameter is 1.

- `timeUntilExpungeSeconds`: If an instance is unreachable for longer than this value, it will be expunged. An expunged task will be killed if it ever comes back. Instances are usually marked as unreachable before they are expunged, but that is not required. The minimum value for this parameter is 2.

For example, if you configure `timeUntilInactive = 60` and `timeUntilExpunge = 120`, a task instance will be expunged if it has been unreachable for more than 120 seconds and a second instance will be started if it has been unreachable for more than 60 seconds.