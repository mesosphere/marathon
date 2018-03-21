

---
title: Unreachable Strategy
---

# Unreachable Strategy

In order for Marathon to provide partition aware unreachable strategy support there are 2 high level events that must occur:

1. Mesos needs to communicate a task is unreachable
2. Marathon must respond to that event if unresolved within a specified amount of time.

Each of these events have configuration options and DC/OS system defaults which are worth review in order to fully understand how and when an unreachable task will be managed by Marathon.

## Apache Mesos Unreachable Strategies

### Inactive Agent Logic and Unreachable Tasks

A task is considered unreachable when its agent is considered inactive. Apache Mesos will mark agents as inactive according to the following:

1. Mesos agent health checks.
2. Agent removal rate limiter.

Regarding agent health checks, the Mesos master flags of control are:

- `--agent_ping_timeout` (default 15s) - The duration after which an attempt to ping the agent is considered a timeout
- `--max_agent_ping_timeouts` (default 5) - The number of consecutive attempts to ping an agent, after which the agent is considered unreachable.


With the Mesos defaults, an agent will be marked inactive after a 75 seconds (15 seconds \* 5) of no communication, independent of the agent removal rate limit.

In DC/OS, the default for `--max_slave_ping_timeouts` is 20 [See dcos-config.yaml](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L449). As such, the Mesos master will consider an agent inactive after 5 minutes (20 \* 15 seconds) of no communication, independent of agent removal rate limit.

When Mesos marks an agent as inactive, Mesos will publish an unreachable task status update for all tasks associated with that agent.

### Agent Removal Rate Limiter

In the previous section, we mentioned that Mesos will consider an agent inactive, assuming that only 1 agent is lost. This is because Apache Mesos has an agent rate limiter which was established prior to partition aware frameworks and is still enforced. The purpose of the rate limiter is to reduce the number of reported lost agents within a specified amount of time through the `--agent_removal_rate_limit` flag.

In Mesos, the default for `--agent_removal_rate_limit` is `None`, which has the effect of reporting agents immediately after the agent health check logic as described above.

The default in DC/OS however is [1/20mins](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L442)
which is 1 agent every 20 minutes. The following hypothetical timeline describes how 2 agents that become lost at the same time will be marked inactive:

- 12:00:00 - Agent 1 goes off-line
- 12:03:00 - Agent 2 goes off-line
- 12:20:00 - Mesos Master marks agent 1 as inactive, and publishes a `TASK_UNREACHABLE` status update for relevant tasks.
- 12:40:00 - Mesos Master marks agent 2 as inactive, and publishes a `TASK_UNREACHABLE` status update for relevant tasks.

The net result is that the amount of time that a agent on DC/OS can be lost or unreachable before Marathon is notified is not always deterministic. It is defined by the number of lost nodes, and the order in which they were lost [^1]. It is important to understand that these tasks are listed by Mesos as Active until this time and Marathon is completely unaware until notified by Mesos.

**NOTE:**
There are 2 JIRAs against Mesos to remove rate limits. One marked as a critical bug ([MESOS-7721](https://issues.apache.org/jira/browse/MESOS-7721))
the other as a major improvement ([MESOS-5948](https://issues.apache.org/jira/browse/MESOS-5948)). There is currently discussion in process to remove this rate limit default from DC/OS.

## Marathon Unreachable Strategy

Marathon has configuration options for working with unreachable tasks by setting the unreachable strategy for a Marathon application as part as the app definition:

```json
"unreachableStrategy": {
  "inactiveAfterSeconds": 0,
  "expungeAfterSeconds": 0
}
```

In order for this to take effect it is necessary to get a `TASK_UNREACHABLE` status update from Apache Mesos. The above `unreachableStrategy` configuration for the app definition specifies how to respond to these `TASK_UNREACHABLE` events, as follows:

1. `inactiveAfterSeconds`: the number of seconds after the `TASK_UNREACHABLE` task update is received that Marathon will launch a replacement task.
2. `expungeAfterSeconds`: the number of seconds after the `TASK_UNREACHABLE` task update is received that Marathon will consider the task fully gone. After this point, if the task ever becomes reachable again, Marathon will kill it.

Between the `inactiveAfterSeconds` and the `expungeAfterSeconds` periods, the app will report an extra task: the replacement task, plus the yet-to-be-expunged unreachable task. "Expunge" is a little odd, in that it is the time from `TASK_UNREACHABLE`. If the `inactiveAfterSeconds` trigger event trips, then the expunge will happen in the time configured regardless of if the task becomes reachable or not. In the case of a large delay in the
return of the lost task, it will be killed immediately after it is seen by the system (assuming it is past expunge time).

When the unreachable strategy is `{0, 0}`, the replacement is nearly immediate, with a replacement commonly occurring (for the test app) within roughly 3 seconds, and the expunge happening within 8-11 seconds. When we include this with the Mesos details described above for the loss of 1 agent, it means that Marathon will replace a task as soon as it is notified that the task is unreachable (which happens when the Mesos Master marks an agent as inactive), and it will expunge the original task as soon as it is reachable. If there is time configured for the strategy, then the event cycle that Marathon uses to manage if a task should be replaced or expunged is the task reconciliation cycle. The importance of this is twofold:

1. The unreachable strategy time has to expire.
2. The next task reconciliation needs to happen.

The task reconciliation is a Marathon system configuration [`--reconciliation_interval`](https://mesosphere.github.io/marathon/docs/command-line-flags.html). The Marathon defined default is 10 minutes. This means that an unreachable strategy which includes `inactiveAfterSeconds` = 60, will have a task replaced between 60 seconds and 11 minutes. The reconciliation interval has the same effect on the expunging of a task.

## DC/OS Unreachable Task Scenarios

The follow scenarios a given for a task going unreachable given a default configuration of a DC/OS cluster. This also assumes that there are resources with required constraints available in the cluster for the replacement task. All marked time references are from the actual loss of an agent from the cluster.

### Scenario 1: UnreachableStrategy `{0, 0}`, 1 agent becomes unresponsive for 4 minutes

- 12:00:00 - Agent 1 stops responding due to a network partition (and is running one task).
- 12:04:00 - The network partition is resolved, and agent 1 starts responding again.

At a task level, Marathon is not made aware that the agent was momentarily not responding; Mesos does not report the  task as unreachable.

### Scenario 2: UnreachableStrategy `{0, 0}`, 1 agent becomes unresponsive for 6 minutes

- 12:00:00 - Agent 1 stops responding due to a network partition (and is running one task).
- 12:05:00 - The Mesos master marks Agent 1 as inactive, and publishes a `TASK_UNREACHABLE` status update.
- 12:05:03 - Shortly after, Marathon replaces 1 tasks for with the `TASK_UNREACHABLE` status was received. Record of the unreachable task is quickly expunged.
- 12:06:00 - Agent 1 starts responding again. The Mesos master marks the agent as reachable, and sends a `TASK_RUNNING`status update.
- 12:06:03 - Marathon kills the previously unreachable task kill.

### Scenario 3: UnreachableStrategy `{0, 300}`, lose 1 node for 5 minutes; node becomes reachable after 6 minutes of original event.

- 12:00:00 - Agent 1 becomes unreachable due to a network partition (and is running 1 task).
- 12:05:00 - The Mesos master marks Agent 1 as inactive, and publishes a `TASK_UNREACHABLE` update for the task associated with it.
- 12:05:00 - Marathon launches a replacement task for which the `TASK_UNREACHABLE` status was received. However, the currently unreachable task is not expunged, and Marathon shows 2 tasks for an application with a target instance count of 1.
- 12:06:00 - Agent 1 becomes starts responding again, and the Mesos master marks it as active. The task associated with agent 1 is still running, so a `TASK_RUNNING` status update is published for it.
- Between 12:10:00 to 12:20:00 - the reachable task is killed, and Marathon reports 1 of 1 for application.

**Note:** The expunge time is 300 seconds which is 5 minutes. 5 minutes after Marathon was notified of the unreachable event is the 10 minute mark.
It is possible that the reconciliation internal just happened prior to the 10 minute mark and the next time to expunge is the next reconciliation time which is at the 20 minute mark.

### Scenario 4: UnreachableStrategy `{0, 0}`, Lose 4 nodes for hours, the task node is last.

- 12:00:00 - 4 nodes are lost
- 12:05:00 - Agent 1 is marked inactive
- 12:25:00 - Agent 2 is marked inactive
- 12:45:00 - Agent 3 is marked inactive.
- 13:05:00 - Agent 4 (the one for which a task is running) is marked inactive, and a `TASK_UNREACHABLE`status update is published to Marathon.
- 13:05:03 - Shortly after, Marathon will replace the task for which the `TASK_UNREACHABLE` status update was received.

### Scenario 5: UnreachableStrategy `{300, 300}`, lose 4 nodes for hours, the task node is last.

- 12:00:00 - Nodes 1, 2, 3, and 4 go off-line
- 12:05:00 - Node 1 is marked inactive
- 12:25:00 - Node 2 is marked inactive
- 12:45:00 - Node 3 is marked inactive
- 13:05:00 - Node 4 is marked inactive (the one running a task)
- Between 13:10:00 and 13:20:00 - Marathon will launch a replacement task for the task that was running on node 4, between 70 and 80 minutes after it went off-line.

### Scenario 6: UnreachableStrategy `{0, 0}`, lose 1 node(no task) for 5 minutes and returns at 6 minute mark; lose task node for an undefined amount of time.

- 12:00:00 - Node 1 goes offline.
- 12:05:00 - Mesos marks agent 1 inactive.
- 12:06:00 - Node 1 comes back online.
- 12:12:00 - Node 2 goes offline (and is running 1 task).
- 12:25:00 - Mesos marks agent 2 as inactive, and publishes a `TASK_UNREACHABLE`status update.
- 12:25:03 - Shortly after, Marathon will replaces the task for which the `TASK_UNREACHABLE` status received.

### Scenario 7: UnreachableStrategy `{86400, 86400}`, Lose 1 node for 25 hours

- 2018-03-01 12:00:00 - Node 1 goes off-line.
- 2018-03-01 12:05:00 - Mesos master marks node 1 as inactive, publishes a `TASK_UNREACHABLE` status update. Marathon is now aware.
- Somewhere between 24h5m and 24h15m after node 1 went offline, Marathon will replace the unreachable task.

## Unreachable Summary

There are 3 Windows of time when dealing with unreachable tasks. The first window is the time it takes Marathon to be notified of a task being unreachable. This is dependent on the number of health check failing nodes leading up to the event with a 5 minute minimum. When Marathon is notified it will respond within another window of time. A task replacement window starts with the inactiveAfterSeconds time which could be immediately and up to the reconciliation time window. The final window is expunging an unreachable task that has become reachable again. This window could be immediately as the task is reachable if the expunge time has elapsed.

[^1]: This has been confirmed in a test where a 5 private agent cluster had a task on 1 agent. The processes on each  node were shutdown with the node hosting the task being shutdown last. The amount of time that lapsed prior to Marathon receiving a `TASK_UNREACHABLE` status update was > 1.25 hours. 
