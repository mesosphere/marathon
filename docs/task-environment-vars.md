---
title: Task Environment Variables
---

# Task Environment Variables

Marathon sets up some environment variables for each task it launches in
addition to those set by Mesos.

## User-defined Data

Each element of the `env` field of each task's app definition describes
an environment variable.

**Note:** No custom prefix will be added to these variable names, even if one is
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

**Note:** It is possible to specify a custom prefix for these variable
names through the `--env_vars_prefix` command line flag.

## App Metadata

- `MARATHON_APP_ID` contains the complete path of the corresponding app
  definition. For example `/httpServer`, or `/webshop/db`.
- `MARATHON_APP_VERSION` contains the version of the app definition which
  was used to start this task. For example `2015-04-02T09:37:00.596Z`.
- `MARATHON_APP_DOCKER_IMAGE` contains the value of the app definition 
  `container.docker.image` value. For example `mesosphere/marathon:latest`, `nginx` or `nginx:1.9.3`.
- `MARATHON_APP_RESOURCE_CPUS` contains the value of the app definition `cpus` value.
- `MARATHON_APP_RESOURCE_MEM` contains the value of the app definition `mem` value, expressed in megabytes.
- `MARATHON_APP_RESOURCE_DISK`  value of the app definition `disk` value, expressed in megabytes.
- `MARATHON_APP_LABELS` contains list of labels of the corresponding app definition. For example: `label1 label2 label3`
- `MARATHON_APP_LABEL_NAME` contains value of label "NAME" of the corresponding app definition. 

**Note:** No custom prefix will be added to these variable names, even if one is
specified via the `--env_vars_prefix` command line flag.

**Note:** Label name will be sanitized: all characters except `a-z A-Z 0-9 _` will be replaced with `_`. 
All keys and values longer than 512 chars will be omitted.

## Task Metadata

- `MESOS_TASK_ID` contains the ID of this task as used by Mesos. For example
  `test3.d10caa92-d91b-11e4-b351-56847afe9799`

## Values Set by Mesos

This is by no means exhaustive!

- `MESOS_SANDBOX` (set by the Docker containerizer)  
  Path within the container to the mount point of the sandbox directory.
