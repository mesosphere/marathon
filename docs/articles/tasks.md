# Tasks

### What is a Task?

A task is a process which is launched by Mesos executor on the Mesos Agent. When Marathon launches an instance of the service definition, Marathon sends a command to Mesos to launch one or more tasks.

### How can I use a Task?
Tasks are automatically created by Mesos once service definition is being launched.

There are several other places where interaction with Tasks is possible. This includes:

##### API endpoints:
In Marathon, there are several API endpoints related to tasks. To learn more, check out the respective `apps`, `pods` and `tasks` sections on the [Marathon REST API page](http://mesosphere.github.io/marathon/api-console/index.html).

##### Marathon Health Checks configuration
`taskKillGracePeriodSeconds` field allows you to set the amount of time between when the executor sends the `SIGTERM` message to gracefully terminate a task and when it kills it by sending `SIGKILL`. Please check the [health checks](health-checks.md) page for more information.

##### Unreachable Strategy
It’s possible to set the task killing timeouts once a task enters the unreachable state. See the Unreachable Strategy page for more information.

##### Environment Variables
It’s possible to provide the task with environmental variables. See the Environment Variables page for more information.

### I want to know more
A more detailed explanation of tasks can be found on [Apache Mesos website](http://mesos.apache.org/documentation/latest/).

### Links
[Instances](instances.md)  
[Instance Lifecycle](instance-lifecycle.md)  
[Task Lifecycle](task-lifecycle.md)  
[Unreachable Strategy](unreachable-strategy.md)
