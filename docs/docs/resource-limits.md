# Resource Limits

Marathon can instruct Mesos to enable resource limits [TODO - LINK TO MESOS PAGE WHEN IT IS AVAILBLE](http://mesos.apache.org/documentation/latest/resource-limits) for launched pods and tasks.

## Apps

Resource limits is specified in apps by the field "resourceLimits". The two valid keys are "cpus" and "mem", and the values may either be "unlimited" or a numeric value greater than or equal to the corresponding requested resource amount.

```
{
  "id": "/dev/bigbusiness",
  "cpus": 1,
  "mem": 4096,
  "resourceLimits": {
    "cpus": "unlimited",
    "mem": 8192
  },
  ...
}
```

### Pods

Similar to apps, pods may also have resourceLimits specified on a per-container basis.

```
{
  "id": "/dev/bigbusiness",
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "resourceLimits": {
        "cpus": "unlimited"
      },
      "endpoints": []
    }
  ],
  ...
}
```

#### Pod "legacySharedCgroups" field

Pods created prior to Marathon 1.10 will have the option `legacySharedCgroups` enabled. This enables old behavior in which containers would share resources with other resources in pods. For example, if you had the following pod, and a task "big-compute-task" which only requested 32MB of memory but really consumed much more, it would consume memory resources from the container "primary-service".

```
{
  "id": "/dev/bigbusiness",
  "legacySharedCgroups": true
  "containers": [
    {
      "name": "primary-service",
      "exec": { "command": { "shell": "./primary-service" } },
      "resources": { "cpus": 1.0, "mem": 1024 },
      "endpoints": []
    },
    {
      "name": "compute",
      "exec": { "command": { "shell": "./big-compute-task" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "endpoints": []
    }
  ],
  ...
}
```

In order to specify `resourceLimits` for pods, `legacySharedCgroups` must be disabled. This is done by simply removing the field from the pod definition.

### OOM killing risk for memory bursting resources

When memory bursting is enabled, it is the responsibility of the task being launched to avoid consuming extra memory if memory is in short supply, otherwise it will be potentially OOM killed.

For more information about Mesos handling of resource limits and OOM killing, please see [TODO - LINK TO MESOS PAGE WHEN IT IS AVAILBLE](http://mesos.apache.org/documentation/latest/resource-limits)
