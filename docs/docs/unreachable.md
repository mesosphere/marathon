
---
title: Unreachable Strategy
---

# Unreachable Strategy

In a distributed system architecture such as Apache Mesos or DC/OS, it is often necessary to have a strategy for what
to do if a node or task becomes unreachable. Unreachable in this sense means Mesos Master is unable to get status information
about the task because it is no longer able to communicate to the Mesos Agent. One strategy is to react slowly to the unreachable
event assuming there is network issue (that doesn't affect the users of the underlying service), or the task is a process
that can run in isolation.  Another strategy is to be more aggressive and assume recovery is needed.

In order for Marathon to provide partition aware unreachable strategy support there are 2 high level events that must occur:

1. Mesos master needs to communicate a task is unreachable
2. Marathon must respond to that event if unresolved within a specified amount of time.

Each of these events have configuration options and DC/OS system defaults which are worth review in order to fully
understand how and when an unreachable task will be managed by Marathon.


## Apache Mesos Unreachable Strategies

### Inactive Agent Logic and Unreachable Tasks

Marathon manages tasks not agents and is provided the status of `TASK_UNREACHABLE` for each task running on an agent marked
as inactive due to health check failure. The sending of `TASK_UNREACHABLE` status by Mesos is controlled by 2 concepts:

1. Mesos agent health checks.
2. Agent rate limiter.

The notification to Marathon of a task becoming unreachable is based on an agent becoming inactive *and* can be delayed
by the agent rate limiter.

Regarding agent health checks, the Mesos master flags of control are:

- `--agent_ping_timeout` (default 15s) - The duration after which an attempt to ping the agent is considered a timeout
- `--max_agent_ping_timeouts` (default 5) - The number of consecutive attempts to ping an agent, after which the agent is considered unreachable.
- `--agent_reregister_timeout` (default 10m) - The timeout within which an agent is expected to re-register with Mesos Master. 
Agents that do not re-register within the timeout will be marked unreachable in the registry; if/when the agent re-registers with the master, any non-partition-aware tasks running on the agent will be terminated.

With the Mesos defaults, an agent will be marked inactive after a 75 seconds (15 seconds \* 5) of no communication,
assuming only 1 node becoming inactive.

In DC/OS, the default for `--max_slave_ping_timeouts` is 20 [See dcos-config.yaml](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L449).
As such, the Mesos master will consider an agent inactive after 5 minutes (20 \* 15 seconds) of no communication.  Again
assuming only 1 node becoming inactive.

When Mesos master marks an agent as inactive and the agent isn't rate limited, Mesos master will publish an unreachable task status
update for all tasks associated with that agent. Where things can be surprising is when we consider the agent rate limiter.

If Mesos Agent does not register with Mesos Master in agent reregister timeout period, then Mesos Master will kill all underlying tasks on that agent.
Default agent reregister timeout is 10 minutes, DC/OS default time is (TODO).


### Agent Removal Rate Limiter

In the previous section, we mentioned that Mesos will consider an agent inactive, assuming that only 1 agent is lost.
This is because Apache Mesos has an agent rate limiter which was established prior to partition aware frameworks
and is still enforced. The purpose of the rate limiter is to reduce the number of reported lost agents within a specified
amount of time through the `--agent_removal_rate_limit` flag.

In Mesos, the default for `--agent_removal_rate_limit` is `None`, which has the effect of reporting agents immediately
after the agent health check logic as described above.

The default in DC/OS however is 1/20mins [See dcos-config.yaml](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L442)
where 1 agent is marked `lost` every 20 minutes. The following hypothetical timeline describes how 2 agents that become lost at the
same time will be marked inactive with PARTITION_AWARE capability enabled:

- 12:00:00 - Agent 1 goes off-line
- 12:03:00 - Agent 2 goes off-line
- 12:05:00 - Mesos Master marks agent 1 as inactive, and publishes a `TASK_UNREACHABLE` status update for relevant tasks.
- 12:25:00 - Mesos Master marks agent 2 as inactive, and publishes a `TASK_UNREACHABLE` status update for relevant tasks.

The net result is that the amount of time that an agent on DC/OS can be lost or unreachable before Marathon is notified is not always deterministic. 
It is defined by the number of lost nodes, and the order in which they were lost [^1]. 
It is important to understand that these tasks are listed as active for Marathon, untill Mesos Master notifies `TASK_UNREACHABLE` for lost Mesos Agent(s).

**NOTE:**
There are 2 JIRAs against Apache Mesos to remove rate limits. One marked as a critical bug ([MESOS-7721](https://issues.apache.org/jira/browse/MESOS-7721))
the other as a major improvement ([MESOS-5948](https://issues.apache.org/jira/browse/MESOS-5948)).


## Marathon Unreachable Strategy

Marathon has configuration options for working with unreachable tasks by setting the unreachable strategy for a Marathon application as part of the app definition:

```json
unreachableStrategy: {
  "inactiveAfterSeconds": 0,
  "expungeAfterSeconds": 0
}
```

In order for this to take effect it is necessary to get a `TASK_UNREACHABLE` status update from Mesos Master. The above
`unreachableStrategy` configuration for the app definition specifies how to respond to these `TASK_UNREACHABLE` events,
as follows:

1. `inactiveAfterSeconds`: the number of seconds Marathon will wait after receiving the `TASK_UNREACHABLE` status for a task
to become reachable again.  After this time Marathon will launch a replacement task.
2. `expungeAfterSeconds`: the number of seconds after the `TASK_UNREACHABLE` task status update is received and only after
a replacement task was launched (based on `inactiveAfterSeconds` expiration) that Marathon will kill the task when it becomes
reachable again.

The `expungeAfterSeconds` must always be equal to or greater than `inactiveAfterSeconds`.  The expunge event *requires* that the
`inactiveAfterSeconds` event occurred first. The expunge time is a minimum time and only occurs after a replacement task
has launched.  If the original task becomes reachable after the `inactiveAfterSeconds` time but before the `expungeAfterSeconds`
than Marathon will report 2 of 1 for the number of tasks running for that app.  When the `expungeAfterSeconds` time
expires the task will be killed. If the original task becomes reachable sometime after the `expungeAfterSeconds` time
it will be killed immediately.

When the unreachable strategy is `{0, 0}`, the replacement is nearly immediate, with a replacement commonly occurring
(for our test app) within roughly 3 seconds, and the expunge happening within 8-11 seconds. When we include this with
the Mesos details described above for the loss of 1 agent, it means that Marathon will replace a task as soon as it is
notified that the task is unreachable (which happens when the Mesos Master marks an agent as inactive), and it will
expunge the original task as soon as it is reachable.

When time other than 0 is configured for the unreachable strategy, the Marathon task reconciliation event cycle is used
to evaluate expiration times for unreachable tasks. The task reconciliation is a Marathon system configuration [`--reconciliation_interval`](https://mesosphere.github.io/marathon/docs/command-line-flags.html).  The Marathon defined
default is 10 minutes. This means that an unreachable strategy which includes `inactiveAfterSeconds` = 60, will have a
task replaced between 60 seconds and 11 minutes. For this example, the 60 seconds `inactiveAfterSeconds` could have just
expired just before the reconciliation, thus launching a replacement task in ~ 60 seconds.  Or the 60 seconds could expire
immediately as a reconciliation window started needing to wait an additional 10 mins for the next reconciliation, thus
replacement occurs ~ 11 mins after receiving unreachable status for the task.  The reconciliation interval has the same
effect on the expunging of a task.


## DC/OS Unreachable Task Scenarios

The following scenarios for an unreachable task with default configuration of a DC/OS cluster. This
assumes that there are resources with required constraints available in the cluster for the replacement task. All marked
time references are from the actual loss of an agent from the cluster.

### Scenario 1: UnreachableStrategy `{0, 0}`, 1 agent becomes unresponsive for 4 minutes

- 12:00:00 - Agent 1 stops responding due to a network partition (and is running one task).
- 12:04:00 - The network partition is resolved, and agent 1 starts responding again.

At a task level, Marathon is not made aware that the agent was momentarily not responding; Mesos Master does not report the
task as unreachable because agent_ping_timeout * max_ping_timeout (15 seconds * 20 = 5 minutes) is not elapsed

### Scenario 2: UnreachableStrategy `{0, 0}`, 1 agent becomes unresponsive for 6 minutes

- 12:00:00 - Agent 1 stops responding due to a network partition (and is running one task).
- 12:05:00 - The Mesos master marks Agent 1 as inactive, and publishes a `TASK_UNREACHABLE` status update.
- 12:05:03 - Shortly after, Marathon replaces 1 task with `TASK_UNREACHABLE` task status on different mesos agent.
- 12:06:00 - Agent 1 starts responding again. The Mesos master marks the agent as reachable, and sends a `TASK_RUNNING` status update.
- 12:06:03 - Marathon kills the previously unreachable task kill.

### Scenario 3: UnreachableStrategy `{0, 300}`, lose 1 agent for 5 minutes; agent becomes reachable after 6 minutes of original event.

- 12:00:00 - Agent 1 becomes unreachable due to a network partition (and is running 1 task).
- 12:05:00 - The Mesos master marks Agent 1 as inactive, and publishes a `TASK_UNREACHABLE` update for the task associated with it.
- 12:05:03 - Marathon launches a replacement task for which the `TASK_UNREACHABLE` status was received.
- 12:06:00 - Agent 1 becomes starts responding again, and the Mesos master marks it as active. The task associated with
agent 1 is still running, so a `TASK_RUNNING` status update is published for it.  Marathon shows 2 tasks running for an
application with a target instance count of 1.
- Between 12:10:00 to 12:20:00 - the reachable task is killed, and Marathon reports 1 of 1 for application.

**Note:** The expunge time is 300 seconds which is 5 minutes. 5 minutes after Marathon was notified of the unreachable
event is the 10 minute mark.  It is possible that the reconciliation internal just happened prior to the 10 minute mark
and the next time to expunge is the next reconciliation time which is at the 20 minute mark.

### Scenario 4: UnreachableStrategy `{0, 0}`, Lose 4 nodes for hours, the task node is last.

- 12:00:00 - 4 nodes are lost
- 12:05:00 - Mesos marks Agent 1 inactive
- 12:25:00 - Mesos marks Agent 2 inactive
- 12:45:00 - Mesos marks Agent 3 inactive
- 13:05:00 - Agent 4 is marked inactive, and a `TASK_UNREACHABLE` status update is published to Marathon.
- 13:05:03 - Shortly after, Marathon will replace the task for which the `TASK_UNREACHABLE` status update was received.

### Scenario 5: UnreachableStrategy `{300, 300}`, lose 4 nodes for hours, the task node is last.

- 12:00:00 - 4 nodes are lost
- 12:05:00 - Mesos marks Agent 1 inactive
- 12:25:00 - Mesos marks Agent 2 inactive
- 12:45:00 - Mesos marks Agent 3 inactive
- 13:05:00 - Agent 4 is marked inactive, and a `TASK_UNREACHABLE` status update is published to Marathon.
- Between 13:10:00 and 13:20:00 - Marathon will launch a replacement task for the task that was running on node 4.

### Scenario 6: UnreachableStrategy `{0, 0}`, lose 1 node(no task) for 5 minutes and returns at 6 minute mark; lose task node for an undefined amount of time.

- 12:00:00 - Node 1 goes offline.
- 12:05:00 - Mesos marks agent 1 inactive.
- 12:06:00 - Node 1 comes back online.
- 12:12:00 - Node 2 goes offline (and is running 1 task).
- 12:25:00 - Mesos marks agent 2 as inactive, and publishes a `TASK_UNREACHABLE` status update.
- 12:25:03 - Shortly after, Marathon will replaces the task for which the `TASK_UNREACHABLE` status received.

**Note:** Normally Marathon would get a `TASK_UNREACHABLE` status for an inactive agent in 5 mins.  In this case, another
agent went inactive and changed the notification time based on the agent rate limiter.

### Scenario 7: UnreachableStrategy `{86400, 86400}`, Lose 1 node for 25 hours

- 2018-03-01 12:00:00 - Node 1 goes off-line.
- 2018-03-01 12:05:00 - Mesos master marks node 1 as inactive, publishes a `TASK_UNREACHABLE` status update. Marathon is now aware.
- 2018-03-02 Between 12:05:00 and 12:15:00 Marathon will replace the unreachable task. Here, tasks may be killed by Mesos Master, as agent did not reregister for 10 minutes.

## Unreachable Summary

There are 4 time windows when dealing with unreachable tasks. The first window is the time it takes Marathon to be notified of a task being unreachable.This is dependent on the number of health check failing nodes leading up to the event with a 5 minute minimum.
Next is Mesos Agent reregister timeout, if agent does not reregister within this time period then Mesos Master will kill all tasks on that agent. 
When Marathon is notified it will respond within another window of time. A task replacement window starts with the `inactiveAfterSeconds` time which could be immediately and up to the reconciliation time window. 
The final window is expunging an unreachable task that has become reachable again. This window could be immediately upon the task becoming reachable if the expunge time has elapsed.

[^1]: This has been confirmed in a test where a 5 private agent cluster had a task on 1 agent. The processes on each  node were shutdown with the node hosting the task being shutdown last. The amount of time that lapsed prior to Marathon receiving a `TASK_UNREACHABLE` status update was > 1.25 hours.
