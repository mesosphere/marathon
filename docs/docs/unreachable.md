
---
title: Unreachable Strategy
---

# Unreachable Strategy

In order for Marathon to provide partition aware unreachable strategy support there are 2 high level events that must occur; 1) Mesos needs to communicate a task is unreachable and 2) Marathon must respond to that event if unresolved within a specified amount of time. Each of these events have configuration options and DCOS system defaults which are worth review in order to fully understand how and when an unreachable task will be managed by Marathon.

## Apache Mesos Unreachable Strategies

Apache Meso's ability to communicate a task / node is unreachable is controlled by 2 concepts; 1) mesos-agent health check and 2) node rate limiter. Regarding agent health checks, the mesos-master flags of control are:
`-max_agent_ping_timeouts` and `-agent_ping_timeout`. While the Mesos defaults are 5 and 15s respectively providing a 75 second notification event by default (assuming the loss of 1 agent). The default for DC/OS for [max_slave_ping_timeouts is 20](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L449)
providing a 5 minute delay window between a lost node and notification to frameworks that the task is unreachable.

Additionally Apache Mesos has a node rate limiter which was established prior to partition aware frameworks and is still enforced. The purpose of the rate limiter is to reduce the number of reported lost nodes / agents within a specified amount of time through the `--agent_removal_rate_limit=VALUE` flag. The default is None, which has the effect of reporting agents immediately after the health check defined above. The default in DC/OS however is [1/20mins](https://github.com/dcos/dcos/blob/9cc6ab28060545cd203c09aa7fa1b9456773d080/gen/dcos-config.yaml#L442)
which is 1 node every 20 mins. With this default, the loss of 2 nodes would result in the first node reporting 5 mins after health checks start to fail that it is unreachable, followed by the 2nd node reporting unreachable 20 mins later. In this scenario, agents and tasks will be reported by Mesos as active for the time between the start of health check failure and the unreachable event as defined here.

The net result is that the amount of time that a node on DC/OS can be lost or unreachable before Marathon is notified is not predictable. It is defined by the number of lost nodes and the order in which they are lost. This was confirmed in a test where a 5 private agent cluster had a task on 1 node. The agent processes on each node were shutdown with the node hosting the task being shutdown last. The amount of time lapsed prior to Marathon receiving a TASK_UNREACHABLE status update was > 1.25 hrs. It is important to understand that these tasks are listed by Mesos as Active until this time and Marathon is completely unaware until notified by Mesos.

**NOTE:**
There are 2 JIRAs against Mesos to remove rate limits. One marked as a critical bug https://issues.apache.org/jira/browse/MESOS-7721
the other as a major improvement. https://issues.apache.org/jira/browse/MESOS-5948. It seems as though the default Mesos behavior is as if there is no rate limit, which begs the question why we don't set DC/OS to not have this rate limit?

## Marathon Unreachable Strategy

Marathon has configuration options for working with unreachable tasks by setting the unreachable strategy for a Marathon application as part as the app definition:

```
unreachableStrategy: {
"inactiveAfterSeconds": 0,
"expungeAfterSeconds": 0
}
```

In order for this to take effect it is necessary to get a TASK_UNREACHABLE status update from Apache Mesos. The above constraints set the minimum time of managing an unreachable strategy event. The configuration for the app definition sets up to time events. The first is `inactiveAfterSeconds` which is the time in seconds after the TASK_UNREACHABLE event from Mesos that Marathon will create a replacement task. The second configuration is `expungeAfterSeconds` which is the amount of time after the TASK_UNREACHABLE that a task will be removed killed provided 1) it was replaced with the inactiveAfterSecond event and 2) the original task (which was unreachable) becomes reachable again. From the time of `inactiveAfterSeconds` until the "expunge" event, the app will report in Marathon 2 of 1 for the number of tasks running for that app. "expunge" is a little odd, in that it is the time from TASK_UNREACHABLE. If the "inactiveAfterSeconds" trigger event trips, then the expunge will happen in the time configured regardless of if the task becomes reachable or not. In the case of a large delay in the
return of the lost task, it will be killed immediately after it is seen by the system (assuming it is past expunge time).

When the unreachable strategy is {0,0}, the replacement is near immediate with a replacement commonly running (for the test app) of roughly 3 seconds. The expunge happening around 8-11 seconds. When we include this with the Mesos details provided above for the loss of 1 agent, it means that Marathon will replace a task as soon as it is notified of the unreachable event and it will expunge the original task as soon as it is reachable. If there is time configured for the strategy, then the event cycle that Marathon uses to managing if a task should be replaced or expunged is the task reconciliation cycle. The importance of this is 2 fold: 1) the unreachable strategy time has to expire and 2) the next task reconciliation needs to happen. The task reconciliation is a Marathon system configuration [--reconciliation_interval](https://mesosphere.github.io/marathon/docs/command-line-flags.html). The Marathon defined default is 10 minutes. This means that an unreachable strategy which includes inactiveAfterSeconds = 60, will have a task replaced between 60 seconds and 10 minutes and 60 seconds. The reconciliation interval has the same effect on the expunging of a task.

## DC/OS Unreachable Task Scenarios

The follow scenarios a given for a task going unreachable given a default configuration of a DC/OS cluster. This also assumes that there are resources with required constraints available in the cluster for the replacement task. All marked time references are from the actual loss of an agent from the cluster.

### Strategy {0,0}, Lose 1 node for 4 mins

Marathon is never aware and Mesos reports task.

### Strategy {0,0}, Lose 1 node for 5 mins; Node becomes reachable after 6 mins of original event.

At 5 min mark, Marathon will replace 1 task when TASK_UNREACHABLE status received.
At 6 min mark, Marathon will kill the task that became reachable.

### Strategy {0,300}, Lose 1 node for 5 mins; Node becomes reachable after 6 mins of original event.

At 5 min mark, Marathon will replace 1 task when TASK_UNREACHABLE status received.
At 6 min mark, Marathon reports number of tasks as 2 of 1 for application.
Somewhere between the 10 min mark and 20 min mark, the reachable task will be killed and Marathon reports 1 of 1 for application.

**Note:** The expunge time is 300 seconds which is 5 mins. 5 mins after Marathon was notified of the unreachable event is the 10 min mark.
It is possible that the reconciliation internal just happened prior to the 10 min mark and the next time to expunge is the next reconciliation time which is at the 20 min mark.

### Strategy {0,0}, Lose 4 nodes for hours, the task node is last.

At 5 min mark, node 1 would have an unreachable event.
At 25 min mark, node 2 would have an unreachable event.
At 45 min mark, node 3 would have an unreachable event.
At 65 min mark, Marathon will replace the task when TASK_UNREACHABLE status received.

### Strategy {300,300}, Lose 4 nodes for hours, the task node is last.

At 5 min mark, node 1 would have an unreachable event.
At 25 min mark, node 2 would have an unreachable event.
At 45 min mark, node 3 would have an unreachable event.
At 65 min mark, Marathon receive TASK_UNREACHABLE status for task.
Somewhere between 70 mins and 80 mins, Marathon will replace the unreachable task.

### Strategy {0,0}, Lose 1 node(no task) for 5 mins and returns at 6 min mark. Lose task node for for undefined amount of time.

At 5 min mark, node 1 would have an unreachable event.
At 25 min mark, Marathon will replace the task when TASK_UNREACHABLE status received.

### Strategy {86400,86400}, Lose 1 node for 25 hours

At 5 min mark, Marathon is aware that task is unreachable.
Somewhere between 24h5m and 24h15m, Marathon will replace the unreachable task.

## Unreachable Summary

There are 3 Windows of time when dealing with unreachable tasks. The first window is the time it takes Marathon to be notified of a task being unreachable. This is dependent on the number of health check failing nodes leading up to the event with a 5 min minimum. When Marathon is notified it will respond within another window of time. A task replacement window starts with the inactiveAfterSeconds time which could be immediately and up to the reconciliation time window. The final window is expunging an unreachable task that has become reachable again. This window could be immediately as the task is reachable if the expunge time has elapsed.
