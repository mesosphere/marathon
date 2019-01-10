# Tasks

## Overview

A task is a process which is launched by Mesos executor on the Mesos Agent. When Marathon launches an instance of the service definition, Marathon sends a command to Mesos to launch one or more tasks.

## How can I use a Task?

Tasks are automatically created by Mesos once a service definition is being launched.

There are several other places where interaction with Tasks is possible. This includes:

## API endpoints:

In Marathon, there are several API endpoints related to tasks. To learn more, check out the respective `apps`, `pods` and `tasks` sections on the [Marathon REST API page](http://mesosphere.github.io/marathon/api-console/index.html).

## Marathon Health Checks configuration

`taskKillGracePeriodSeconds` field allows you to set the amount of time between when the executor sends the `SIGTERM` message to gracefully terminate a task and when it kills it by sending `SIGKILL`. Please check the [health checks](health-checks.md) page for more information.

## Unreachable Strategy

There are a parameters to control the Marathon's treatment of unreachable tasks, with regards to how quickly a replacement task is launched, and how quickly Marathon treats the task as permanently gone. See the Unreachable Strategy page for more information.

## Environment Variables

Tasks receive various environment variables, describing networking ports, Mesos metadata, and Marathon metadata. It’s also possible to specify custom environment variables. See the Environment Variables page for more information.

## I want to know more

A more detailed explanation of tasks can be found on [Apache Mesos website](http://mesos.apache.org/documentation/latest/).

## Links

* [Instances](instances.md)  
* [Instance Lifecycle](instance-lifecycle.md)  
* [Task Lifecycle](task-lifecycle.md)  
* [Unreachable Strategy](unreachable-strategy.md)
