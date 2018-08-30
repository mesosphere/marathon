---
title: Maintenance Mode
---

# Maintenance Mode

As of version 1.6, Marathon has simple built-in support for [Mesos Maintenance Primitives](http://mesos.apache.org/documentation/latest/maintenance/) by declining offers for agents undergoing a maintenance window. As of Marathon 1.7, this behavior is enabled by default, and can be controlled via the `maintenance_behavior` [command-line flag](./command-line-flags.html).

Maintenance Behavior has the following modes:

- `decline_offers` (default) - Marathon will decline offers for agents currently undergoing a maintenance window. Furthermore, the flag `draining_seconds` can be specified to cause Marathon to begin declining offers for an agent before its maintenance window begins.
- `disabled` - Marathon ignores agent maintenance windows, accepting offers and launching tasks on agents regardless of their maintenance window state.

## Limitations

Automatic draining is not yet implemented. If an agent (marked under maintenance or not) is shut down, Marathon will not receive terminal task statuses for the tasks that were running on the agent. As such, the tasks will be seen as unreachable and relaunched per the configured [unreachable strategy](./unreachable.html). To avoid this, you can manually kill tasks on the agents currently under a maintenance window before the agent is fully shut down.

If you'd prefer to overscale rather than underscale during the transition, you can scale the application up by N instances, and then kill-and-scale back down to the original instance count.

The efforts to implement richer Maintenance Mode behavior are tracked in [this JIRA issue](https://jira.mesosphere.com/browse/MARATHON-3216).
