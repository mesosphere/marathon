---
title: Task Environment Variables
---

# Task Environment Variables

Marathon sets up some environment variables for each task it launches in
addition to those set by Mesos.

## User-defined Data

Each element of the `env` field of each task's app definition describes
an environment variable.

_Note: No custom prefix will be added to these variable names, even if one is
specified via the `--env_vars_prefix` command line flag.

## Host Ports

Host ports are the ports at which the application is reachable on the host running
the Mesos slave.

For the Mesos executor, these are also the ports that the application
should listen to. For the docker executor in BRIDGED mode, the application
should actually bind to the container port which is typically the same
for each task since the containers use their own network stack.

One environment variable is set for each assigned port resource.
These are named `PORT0` through `PORT{N-1}` where `N` is the number of
assigned ports. In addition provided there is at least one assigned
port, then `PORT` has the same value as `PORT0`.

The `PORTS` variable contains a comma-separated list of all assigned
host ports.

_Note: It is possible to specify a custom prefix for these variable
names through the `--env_vars_prefix` command line flag.

## App Metadata

- `MARATHON_APP_ID` contains the complete path of the corresponding app
  definition. For example `/httpServer`, or `/webshop/db`.
- `MARATHON_APP_VERSION` contains the version of the app definition which
  was used to start this task. For example `2015-04-02T09:37:00.596Z`.

_Note: No custom prefix will be added to these variable names, even if one is
specified via the `--env_vars_prefix` command line flag.

## Task Metadata

- `MESOS_TASK_ID` contains the ID of this task as used by Mesos. For example
  `test3.d10caa92-d91b-11e4-b351-56847afe9799`

## Values Set by Mesos

This is by no means exhaustive!

- `MESOS_SANDBOX` (set by the Docker containerizer)  
  Path within the container to the mount point of the sandbox directory.
