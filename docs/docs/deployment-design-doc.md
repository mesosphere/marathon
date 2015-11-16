---
title: Advanced Deployment Design
---

# DESIGN: Advanced Deployment with Marathon

IMPORTANT: This is the original design document. Some of the features are implemented, some are not, some are 
implemented differently. Do not use as a reference on how to configure your deployments.

## Problem Statement

One of the most common problems facing developers and operators is how to roll out new versions of applications uninterruptibly.  At the root, this process consists of two phases: starting a set of processes, and stopping a (disjoint) set of processes.  The details of how these phases are executed in a multi-datacenter environment may vary wildly from site to site.  For example, these phases may or may not happen concurrently.  Additional restrictions may apply, such as how long to wait before traffic is routed to a newly created process.  Another typical aspect of this task is the configuration of external systems, such as reverse proxies for load balancing.

In the Apache Mesos model, where process placement is typically done dynamically, these configuration tasks must happen in response to the resource allocator and scheduler.  The purpose of this exercise is to explore what features Marathon can provide to ease the entire deployment process, including the configuration of supporting systems.  Marathon's current feature set is opinionated regarding how to implement custom application provisioning logic -- that is, Marathon provides useful primitives that can be composed to solve a wide variety of problems.  Marathon exposes this functionality through a simple _RESTful_ HTTP interface to maximize scriptability from programming languages already in wide use for operations automation.

## Example Use Cases

### Rolling restart

- Given that `N` instances of an application are currently running in the
  cluster, restart them

- Ensure that there are at least `k` percent healthy instances available for servicing
  requests at all times

- Allow for dynamic reconfiguration of load balancers such that no production
  traffic is routed to a down instance

### Rolling upgrade

- Similar to a rolling restart (outlined above)

- Operators should be able to control the transition strategy

    - An example transition strategy:

        - Bring up the full complement of new application instances

        - Optionally wait for health checks to pass on new instances before redirecting traffic

        - Reconfigure load balancers to direct traffic to the new instances

        - Optionally wait before killling old instances in case of a rollback

        - Kill all of the old application instances

    - Another strategy:

        - Bring up new instances one-at-a-time

            - As each instance comes up, add it to the load balancer

            - Remove an old instance from the load balancer before killing it

    - Yet another strategy:

        - Bring up new instances incrementally in batches, e.g. _5%, 25%, 100%_

        - Perform health checks on each batch, rolling back on failure

        - On successfully deploying all batches of the new version, transition traffic
          over to the new instances gradually via load balancer configuration

## Proposed New Features

### Task health checks

- Health checks may be specified per applicaiton, to be run against that application's tasks.

- The default health check defers to Mesos' knowledge of the task state  
  `TASK_RUNNING => healthy`

- Initially, HTTP and TCP health checks are supported.

- Marathon in turn provides a `health` member of the task resource
  via the _REST_ API.

- Health checks begin immediately after the process is up, but failures are ignored within `gracePeriodSeconds`, until a task becomes healthy for the first time.

- Tasks that do not become healthy after `maxConsecutiveFailures` are killed.

- A health check definition looks like this:

    ```json
    {
      "type": "http",
      "uri": "/api/health",
      "portIndex": 0,
      "gracePeriodSeconds": 30,
      "timeoutSeconds": 30,
      "maxConsecutiveFailures": 0
    }
    ```

    OR

    ```json
    {
      "type": "tcp",
      "portIndex": 0,
      "gracePeriodSeconds": 30,
      "timeoutSeconds": 30,
      "maxConsecutiveFailures": 0
    }
    ```

- The application health lifecycle is represented by the finite state machine in figure 1 below.  In the diagram:

    - `i` is the number of requested instances
    - `r` is the number of running instances
    - `h` is the number of healthy instances

    ![lifecycle]({{ site.baseurl }}/img/app-state.png "Application health lifecycle")  
    Figure 1: The Application Health Lifecycle


### Immutable application definitions

- This allows apps to be properly versioned and opens the door to rollbacks

- Versions are derived from time stamps and exposed to users in human-friendly
  ISO date format

### Hierarchical Groups

- We propose a new user-defined n-ary tree of groups, with applications as leaves.
      Groups are versioned, like applications.

- Groups may contain groups or apps (but not both)

- There is one root group at `/v2/groups/`

- The definition of a simple (empty) group looks like this:

    ```json
    { "id": "/test" }
    ```

- The definition of a group looks like this if it contains groups:

    ```json
    {
      "id": "/test",
      "groups": [
        {
          "id": "/test/product-a"
          ...
        }
      ],
      "dependencies": []
    }
    ```

- The definition of a group looks like this if it contains apps:

    ```json
    {
      "id": "/test/product-a",
      "apps": [
        {
          "id": "/test/product-a/rails-frontend-1.3.2",
          "cmd": "tar -xf rails*.tgz && start-postgres.sh",
          "cpus": 1.5,
          "mem": 128,
          "uris": [
            "http://artifacts.mycompany.com/product-a/rails-frontend-1.3.2.tgz"
          ]
        },
        {
          "id": "/test/product-a/pg-backend-2.5.7",
          "cmd": "tar -xf pg*.tgz && start-postgres.sh",
          "cpus": 2,
          "mem": 512,
          "uris": [
            "http://artifacts.mycompany.com/product-a/pg-backend-2.5.7.tgz"
          ]
        }
      ],
      "dependencies": []
    }
    ```

### Dependencies

- The API will allow clients to declare that an entity (a group or an application)
  "depends on" another entity.

    - The "depends on" relationships are used in part to determine the health of the entity

    - The "depends on" relationships are used to manage scaling or restart of the namespace subtree.

    - Dependencies are specified as follows:

        ```json
        {
          "id": "/test/product-a/frontend",
          "dependencies": ["/test/product-a/backend"]
        }
        ```

    - Figure 2 (below) illustrates a simple group hierarchy with a single dependency
      defined.

      ![hierarchy]({{ site.baseurl }}/img/apps.png "Namespaced Application Groups with a Dependency")      
      Figure 2: Namespaced Application Groups with a Dependency

### Managed Scaling

- There is an endpoint that updates the instances parameter of each descendent application, maintaining the relative ratios among the
  members and taking into account inter-group dependencies

- **TODO**: _More clearly define scaling semantics_

### Richer API support for events

- New _REST_ endpoint for per-app event streams (chunked transfer), newline-delimited

- This is both more granular and more amenable to consumption by
  simple scripts than the currently supported web hook

#### Overview of Proposed API Examples

##### Create an App

```
PUT /v2/apps/product-a/frontend/play
```
```json
{
  "cmd": "tar -xf *.tgz && bin/start-play.sh",
  "uri": "http://artifacts.acme.com/rel/app-1.2.3.tgz",
  "healthChecks": [
    {
      "type": "http",
      "uri": "/api/health",
      "portIndex": 0,
      "acceptableStatus": [200],
      "initialDelaySeconds": 30,
      "timeoutSeconds": 30,
      "maxConsecutiveFailures": 0
    }
  ]
}
```

OR

```
POST /v2/apps/product-a/frontend
```
```json
{
  "id": "play",
  "cmd": "tar -xf *.tgz && bin/start-play.sh",
  "uri": "http://artifacts.acme.com/rel/app-1.2.3.tgz",
  "healthChecks": [
    {
      "type": "http",
      "uri": "/api/health",
      "portIndex": 0,
      "acceptableStatus": [200],
      "initialDelaySeconds": 30,
      "timeoutSeconds": 30,
      "maxConsecutiveFailures": 0
    }
  ]
}
```

Creating or updating a multitude of apps:  

```
PUT /v2/apps
```
```json
[
  {
    "id": "/product-a/frontend/play",
    "cmd": "tar -xf *.tgz && bin/start-play.sh",
    "uri": "http://artifacts.acme.com/rel/app-1.2.3.tgz"
  },
  {
    "id": "/product-a/frontend/fun",
    "cmd": "tar -xf *.tgz && bin/start-play.sh",
    "uri": "http://artifacts.acme.com/rel/app-1.2.3.tgz",
    "healthChecks": [
      {
        "type": "tcp",
        "portIndex": 0,
        "gracePeriodSeconds": 30,
        "timeoutSeconds": 30,
        "maxConsecutiveFailures": 0
      }
    ]
  }
]
```

##### Declare a dependency

Add the dependency from a group to a group:

```
PUT /v2/groups/product-a/frontend
```
```json
{ "dependencies": ["backend"] }
```

or from an app to a group

```
PUT /v2/apps/product-a/frontend/play
```
```json
{ "dependencies": ["../backend"] }
```

##### Create a group

`POST` or `PUT` a group anywhere in the hierarchy (behaves like `mkdir -p` in that any necessary groups are implicitly created)

```
POST /v2/groups/test
```
```json
{
  "id": "product-a",
  "upgradeStrategy": {
    "minimumHealthCapacity": 0.5
  },
  "apps": [
    {
      "id": "rails-frontend-1.3.2",
      "cmd": "tar -xf rails*.tgz && start-postgres.sh",
      "cpus": 1.5,
      "mem": 128,
      "uris": [
        "http://artifacts.mycompany.com/productA/rails-frontend-1.3.2.tgz"
      ]
    },
    {
      "id": "pg-backend-2.5.7",
      "cmd": "tar -xf pg*.tgz && start-postgres.sh",
      "cpus": 2,
      "mem": 512,
      "uris": [
        "http://artifacts.mycompany.com/productA/pg-backend-2.5.7.tgz"
      ]
    }
  ]
}
```

OR

```
PUT /v2/groups/test/product-a
```
```json
{
  "apps": [
    {
      "id": "rails-frontend-1.3.2",
      "cmd": "tar -xf rails*.tgz && start-postgres.sh",
      "cpus": 1.5,
      "mem": 128,
      "uris": [
        "http://artifacts.mycompany.com/product-a/rails-frontend-1.3.2.tgz"
      ]
    },
    {
      "id": "pg-backend-2.5.7",
      "cmd": "tar -xf pg*.tgz && start-postgres.sh",
      "cpus": 2,
      "mem": 512,
      "uris": [
        "http://artifacts.mycompany.com/product-a/pg-backend-2.5.7.tgz"
      ]
    }
  ]
}
```

##### View a group's data

```
GET /v2/groups/product-a/frontend
```
```json
{
  "." : {
    "id": "/product-a/frontend",
    "dependencies": ["/product-a/backend"],
    "apps": [
      {
        "id": "/product-a/frontend/play",
        "version": "2014-03-01T23:29:30.158Z",
        ...
      },
      {
        "id": "/product-a/frontend/ehcache",
        "version": "2014-03-01T23:13:21.247Z",
        ...
      }
    ],
    "version": "2014-03-01T23:29:30.158Z"
  }
}
```

OR

```
GET /v2/groups/product-a
```
```json
{
  "." : {
    "id": "/product-a",
    "groups": [
      {
        "id": "/product-a/frontend",
        "dependencies": ["/product-a/backend"],
        "apps": [
          {
            "id": "/product-a/frontend/play",
            "version": "2014-03-01T23:29:30.158Z",
            ...
          },
          {
            "id": "/product-a/frontend/ehcache",
            "version": "2014-03-01T23:13:21.247Z",
            ...
          }
        ],
        "version": "2014-03-01T23:29:30.158Z"
      }
    ],
    "version": "2014-03-01T23:29:30.158Z"
  }
}
```

##### Roll back to a previous configuration of a group

```
PUT /v2/groups/myGroup
```
```json
{ "version": "2014-02-07T02:30Z" }
```

##### Scale a group, maintaining relative instance ratios (creates a new version of the group and its apps)

```
PUT /v2/groups/myGroup
```

```json
{ "scaleBy": 2 }
```

##### Destroy a group

```
DELETE /v2/groups/myGroup
```

##### List apps at a certain point in the namespace

```
GET /v2/apps/product-a/frontend/*
```

```json
{
  "*": [
    {
      "id": "/product-a/frontend/play",
      "version": "2014-03-01T23:29:30.158Z",
      ...
    },
    {
      "id": "/product-a/frontend/ehcache",
      "version": "2014-03-01T23:13:21.247Z",
      ...
    }
  ]
}
```

OR

```
GET /v2/apps/product-a/*
```
```json
{
  "*": [
    {
      "id": "/product-a/frontend/play",
      "version": "2014-03-01T23:29:30.158Z",
      ...
    },
    {
      "id": "/product-a/frontend/ehcache",
      "version": "2014-03-01T23:13:21.247Z",
      ...
    },
    {
      "id": "/product-a/backend/pgsql",
      "version": "2014-03-01T23:11:18.937Z",
      ...
    }
  ]
}
```

##### Update an app

```
PUT /v2/apps/product-a/frontend/play
```

```json
{ "instances": 8 }
```

##### Kill a task

```
DELETE /v2/tasks/product-a/frontend/play/task_xxxx?scale=true
```

##### Get a list of apps in a subtree

```
GET /v2/apps/*
GET /v2/apps/product-a/*
GET /v2/apps/product-a/frontend/*
```

##### Get a list of tasks in a subtree

```
GET /v2/apps/*/task
GET /v2/apps/product-a/*/task
GET /v2/apps/product-a/frontend/*/task
GET /v2/apps/product-a/frontend/play/*/task
```



##### Check the health of a task (not implemented)

A task is healthy if its latest health check passed.

```
GET /v2/health/product-a/frontend/play/task_xxxx
```

##### Check the health of an app (not implemented)

An app's health is one of ["healthy", "unhealthy", "scaling"]

```
GET /v2/health/product-a/frontend/play
```

##### Check the health of a group (not implemented)

A group is "healthy" iff all of its apps are healthy.

```
GET /v2/health/groups/product-a
```

##### List the health of all apps in a group (not implemented)

```
GET /v2/health/groups/product-a/*
```

##### Get a stream of events for an app (not implemented)

```
GET /v2/events/product-a/frontend/play
```
