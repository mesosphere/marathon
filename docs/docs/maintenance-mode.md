---
title: Maintenance Mode
---

# Maintenance Mode

As of version 1.6, Marathon has simple built-in support for [Mesos Maintenance Primitives](http://mesos.apache.org/documentation/latest/maintenance/).

Operators regularly need to perform maintenance tasks on machines that comprise a Mesos cluster. Most Mesos upgrades can be done without affecting running tasks, but there are situations where maintenance may affect running tasks. In order to meet Service Level Agreements or to ensure uninterrupted services for their end users, Marathon is respecting configured maintenance windows. 
If this feature is enabled, Marathon is not scheduling tasks to agents currently within a maintenance window. Furthermore Marathon can decline offers before a maintenance window, if the according parameter is configured via command line argument.

## How it works
The current implementation is respecting configured maintenance windows from Mesos agents. If Marathon receives an offer with an included maintenance window, Marathon will check this agent is currently within this period or if the configured time before the window is reached. If one of this checks is true, Marathon will not use this offer to start a new task.

## Limitations
As described, Marathon will not start tasks on certain offers. But Marathon will also not migrate tasks from agents close to a maintenance window to other agents. In case of an agent maintenance, Marathon would detect the unreachable agent after receiving the according Mesos task status updates. Marathon would then restart these unreachable tasks on other agents. If your application is not allowed to have downtime, you need to manually migrate the tasks.

The efforts to enhance the current implementation are tracked in [this JIRA issue](https://jira.mesosphere.com/browse/MARATHON-3216).

## How to configure
This feature needs to be activated via command line argument. You need to add `maintenance_mode` to the set of `--enable_features` in your marathon startup arguments.
If you want to configure a duration before the maintenance window, in which offers are also declined, you need to add the `draining_seconds` command line argument. The configured duration is in seconds.

Please see [command-line-flags.html] for further informations.
