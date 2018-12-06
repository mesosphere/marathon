# Tasks

### What is a Task?

Task is a process which is launched by Mesos executor on the Mesos Agent. When Marathon launches an instance of the application definition, Marathon sends a command to Mesos to launch one or more tasks.

### How can I use a Task?
Tasks are automatically created by Mesos once application definition is being launched.

There are several other places where interaction with Tasks is possible. This includes:

##### API endpoints:
 * `/v2/apps/<app_id>/tasks`: Lists all running tasks for a provided application id, and gives you task specific information such as agent id, host, task state and so on. It’s also possible to kill tasks using this endpoint.
 * `/v2/tasks`: Lists all running tasks, and gives you task specific information such as agent id, host, task state and so on.
 * `/v2/tasks/delete`: Endpoint for deleting one or more tasks

##### Marathon Health checks
taskKillGracePeriodSeconds field allows you to set the amount of time between when the executor sends the SIGTERM message to gracefully terminate a task and when it kills it by sending SIGKILL.

##### Unreachable Strategy
It’s possible to set the taks killing timeouts once a task enters unreachable state. See the Unreachable Strategy page for more information.

##### Environment Variables
It’s possible to pass some environmental variables to the task. See the Environment Variables page for more information.

### I want to know more
More detailed explanation of tasks can me found on [Apache Mesos website](http://mesos.apache.org/documentation/latest/).

### Links
[Instances](instances.md)  
[Instance Lifecycle](instance-lifecycle.md)  
[Task Lifecycle](task-lifecycle.md)  
[Unreachable Strategy](unreachable-strategy.md)  
[Tasks API](http://mesosphere.github.io/marathon/api-console/index.html) (see the `v2/tasks` section)

