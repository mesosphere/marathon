---
title: Maintenance Mode
---

# Maintenance Mode

As of version 1.6, Marathon has primitive support for [Mesos Maintenance Primitives](http://mesos.apache.org/documentation/latest/maintenance/) by declining offers for agents with scheduled maintenance under [DRAIN mode](http://mesos.apache.org/documentation/latest/maintenance/#how-does-it-work). As of Marathon 1.7, this behavior is enabled by default, and can be disabled via the `--disable_maintenance_mode` [command-line flag](./command-line-flags.html).

For clarity,

- `--maintenance_mode` (default) - Marathon will decline offers for agents currently undergoing a maintenance window. Furthermore, the flag `draining_seconds` can be specified to cause Marathon to begin declining offers for an agent before its maintenance window begins.
- `--disable_maintenance_mode` - Marathon ignores agent maintenance windows, accepting offers and launching tasks on agents regardless of their maintenance window state.

## Limitations

Automatic draining is not yet implemented. If an agent (with scheduled maintenance or not) is shut down, Marathon will not receive terminal task statuses for the tasks that were running on the agent. As such, the tasks will be seen as unreachable and relaunched per the configured [unreachable strategy](./unreachable.html). To avoid this, you can manually kill tasks on the agents currently under a maintenance window before the agent is fully shut down.

If you'd prefer to overscale rather than underscale during the transition, you can scale the application up by N instances, and then kill-and-scale back down to the original instance count.

The efforts to implement richer maintenance mode behavior are tracked in [MARATHON-3216](https://jira.mesosphere.com/browse/MARATHON-3216).
