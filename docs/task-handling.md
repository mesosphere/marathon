---
title: Task Handling
---

# Task Handling

Marathon handles tasks in the following ways. You can consult the Marathon logs for these messages or query the status of the task via the [events stream](http://mesosphere.github.io/marathon/docs/event-bus.html) of the [Marathon REST API](https://mesosphere.github.io/marathon/docs/generated/api.html) (/v2/events).

You can [configure Marathon's behavior when a task is unreachable](configure-task-handling.md).

<p class="text-center">
  <img src="{{site.baseurl}}/img/task-handling.png" width="700" height="700" alt=""><br>
  <em>Figure 1: Task Handling Flow</em>
</p>

## Terminal states

```
case TASK_ERROR => Error
```
The task description contains an error. After Marathon marks the task as an error, it expunges the task and starts a new one.

```
case TASK_FAILED => Failed
```
The task failed to finish successfully. After Marathon marks the task as failed, it expunges the task and starts a new one.

```
case TASK_DROPPED => Dropped
```
The task failed to launch because of a transient error. The task's executor never started running. Unlike TASK_ERROR, the task description is valid -- attempting to launch the task again may be successful.

```
case TASK_GONE => Gone
```

The task was running on an agent that has been shutdown (e.g., the agent become partitioned, rebooted, and then reconnected to the master; any tasks running before the reboot will transition from UNREACHABLE to GONE). The task is no longer running. After Marathon marks the task as gone, it expunges the task and starts a new one.

```
case TASK_GONE_BY_OPERATOR => Gone
```
The task was running on an agent that the master cannot contact; the operator has asserted that the agent has been shutdown, but this has not been directly confirmed by the master. If the operator is correct, the task is not running and this is a terminal state; if the operator is mistaken, the task might still be running, and might return to the RUNNING state in the future. After Marathon marks the task as failed, it expunges the task and starts a new one.    

```
case TASK_FINISHED => Finished
```
The task finished successfully.

```
case TASK_UNKNOWN => Unknown
```
The mster has no knowledge of the task. This is typically because either (a) the master never had knowledge of the task, or (b) the master forgot about the task because it garbaged collected its metadata about the task. The task may or may not still be running. When Marathon receives the Unknown message, it expunges the task and starts a new one.

```
case TASK_KILLED => Killed
```
The task was killed by the executor.


**Note:** Mesos also sends a deprecated `TASK_LOST` message in certain situations. A future version of Mesos will no longer send this message. Marathon may receive a `TASK_LOST` message if a Mesos agent is removed because a new Mesos agent registers with the same IP address. When a `TASK_LOST` message is received, [Marathon will look at the `TASK_LOST` reason that is sent along with the status update](https://github.com/mesosphere/marathon/blob/master/src/main/scala/mesosphere/marathon/core/task/state/TaskConditionMapping.scala) and use it to infer `TASK_GONE`, `TASK_UNREACHABLE`, or `TASK_UNKOWN`.


## Non-terminal states

```
case TASK_STAGING => Staging
```
Initial state: task is staging.

```
case TASK_STARTING => Starting
```
The task is being launched by the executor.

```
case TASK_RUNNING => Running
```
Task is running.

```
case TASK_KILLING => Killing
```
The task is being killed by the executor.

```
case TASK_UNREACHABLE => Unreachable
```
The task was running on an agent that has lost contact with the master, typically due to a network failure or partition. The task may or may not still be running. When Marathon receives a task unreachable message, it starts a replacement task. If the time unreachable exceeds 15 minutes, Marathon marks the task as Unknown and then expunges the task.
