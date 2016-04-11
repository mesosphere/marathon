---
title: Stateful Applications Using Persistent Volumes
---

# Stateful Applications Using Persistent Volumes

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Adapted in Marathon Version 1.0 <br/>
  The Persistent Storage functionality is considered beta, so use this feature at your own risk. We might add, change, or delete any functionality described in this document.
</div>

Marathon applications lose their state when they terminate and are relaunched. In some contexts, for instance, if your application uses MySQL, you’ll want your application to preserve its state. You can create a stateful application by specifying a local persistent volume.

When you specify a local volume or volumes, tasks and their associated data are “pinned” to the node they are first launched on and will be relaunched on that node if they terminate. The resources the application requires are also reserved, and Marathon will implicitly reserve an appropriate amount of disk space (as declared in the volume via `persistent.size`) in addition to the sandbox `disk` size you specify as part of the AppDefinition. Local volumes enable stateful tasks because the task can be restarted without data loss.

## Configuration options

A persistent volume is configured using the following options:

- `containerPath`: The path where your application will read and write data. This can currently be only relative and non-nested (`"data"` but not `"/data"` and not `"/var/data"` and not `"var/data"`). You will find an example of how to configure an application with persistent volumes and an absolute and nested path.
- `mode`: The mode of the volume. Currently, `"RW"` is the only possible value.
- `persistent.size`: The size of the persistent volume in MiBs.

Additionally, you need to set a `residency` in order to tell Marathon to setup a stateful application. Currently, the only valid option for this is
```
"residency": {
  "taskLostBehavior": "WAIT_FOREVER"
}
```

### Example:
```
{
  "containerPath": "data",
  "mode": "RW",
  "persistent": {
    "size": 10
  }
}
```

## Benefits of using Local Persistent Volumes

- All resources needed to run tasks of your stateful service are dynamically reserved, thus ensuring the ability to relaunch the task on the same node using the same volume when needed.
- You don't need constraints to pin a task to a particular agent where its data resides
- You can still use constraints to specify distribution logic
- Marathon lets you locate and destroy an unused persistent volume if you don't need it anymore

## Prerequisites

In order to be able to create stateful applications using local persistent volumes in Marathon, you need to set 2 command line flags which Marathon will use to reserve/unreserve resources and create/destroy volumes.

- `--mesos_authentication_principal`: You can currently just choose any that suits your needs. However, if you setup ACLs on your Mesos master, this must be an authenticated and authorized prinicpal.
- `--mesos_role`: This should be a unique role and will be used implicitly (you don't need to configure the Mesos master via `--roles`)

## Scaling stateful applications

When you scale your app down, the volumes associated with the terminated instances are detached and all resources are still reserved. At this point, you may delete the tasks via the Marathon REST API which will free reserved resources and destroy the persistent volumes. You may wish to leave them in the detached state, however, if you think you will be scaling your app up again; the data on the volume will still be there. Since all the resources your application needs are still reserved when a volume is detached, you may wish to destroy detached volumes in order to allow other applications and frameworks to use the resources.

**Note:** If your app is deleted, any associated volumes and reserved resources will also be deleted.
**Note:** Mesos will currently not remove the data but might do so in the future.

### Upgrading/restarting stateful applications

The default `UpgradeStrategy` for a stateful application has a `minimumHealthCapacity` of `0.5` and a `maximumOverCapacity` of `0`. If you override this default, your definition must stay below these values in order to pass validation. This is because Marathon needs to kill old tasks before starting new ones, so the new versions can take over reservations and volumes, and because Marathon cannot create additional tasks (as a `maximumOverCapacity > 0` would induce) in order to prevent additional volume creation. _Note_: for a stateful application, Marathon will never start more instances than specified, and will rather kill old instances than creating new ones during an upgrade or restart.

## Under the Hood

Marathon leverages three Mesos features for running stateful applications: [dynamic reservations](http://mesos.apache.org/documentation/latest/reservation/), reservation labels and [persistent volumes](http://mesos.apache.org/documentation/latest/persistent-volume/). In contrast to static reservations, dynamic reservations are created at runtime for a given role, and will associate resources with a combination of `frameworkId` and `taskId` using reservation labels. As a result, Marathon ensures its ability to restart a stateful task after it has terminated for some reason, as the used resources will not be offered to other frameworks that are not configured to use this role. Please read the note about [non-unique roles](#non-unique-roles).

Persistent volumes will be created to hold your application's stateful data. Because persistent volumes are local to an agent, the stateful task using this data will be pinned to the agent it was initially launched on, and will be relaunched on this node whenever needed. You do not need to specify any constraints for this to work – when needing to launch a task, Marathon will accept a matching Mesos offer, dynamically reserve the resources required for the task, create persistent volumes and make sure the task is always restarted using these reserved resources so that it can access the existing data.

Once a task that used persistent volumes has terminated, it's metadata will be kept and is eventually used to launch a replacement task when needed. For example, if you scale down from 5 to 3 instances, you will see 2 tasks in `Waiting` state along with the information about the persistent volumes that were in use by these tasks, and the agents on which they are placed. Marathon will not unreserve those resources, and will not destroy the volumes. Whenever you scale up again, Marathon will attempt to launch tasks that use those existing reservations and volumes as soon as it gets a Mesos offer containing the labeled resources. Marathon will only schedule unreserve/destroy operations when

- the application is deleted (in which case volumes of all its tasks are destroyed, and all reservations are deleted)
- you explicitly delete one or more suspended tasks with a `wipe=true` flag

If reserving resources or creating persistent volumes fails, the created task will timeout after the configured `task_reservation_timeout` (default: 20 seconds) and a new reservation attempt will be made. In case a task is `LOST` (because its agent is disconnected or crashed), the reservations and volumes will not timeout and you need to manually delete and wipe the task in order to let Marathon launch a new one.

## Potential Pitfalls

You should be aware of the following issues and limitations when using stateful applications in Marathon that make use of dynamic resevations and persistent volumes.

### Static Reservations

Dynamic reservations can only be created for unreserved resources. If you specify an agent's resources to be reserved for a role via the Mesos `--resources` or `--default_role` flag, these resources cannot be used for dynamic reservations. In addition, if Marathon is started with the `--default_accepted_resource_roles` flag specifying a value that does not contain `*`, your AppDefinition should explicitly specify `"acceptedResourceRoles": ["*"]` in order to allow usage and reservation of unreserved cluster resources.

### Resource requirements

The resource requirements of a stateful application can currently **not** be changed. That means that your initial volume size, cpu usage, mem requirements etc. cannot be changed once you've posted the AppDefinition.

### Replication and Backups

Because persistent volumes are pinned to nodes, they are no longer reachable if the node is disconnected from the cluster, e.g. due to a network partition or a crashed agent. If the stateful service does not take care of data replication on its own, you need to manually take care of replicating your data or create backups, respectively.

In case an agent re-registers with the cluster and offers its resources, Marathon is eventually able to relaunch a task there. If a node does not re-register with the cluster, Marathon will wait forever to receive expected offers, as it's goal is to re-use the existing data. If the agent is not expected to come back, you can manually delete the relevant tasks by adding a `wipe=true` flag, and Marathon will eventually launch a new task with a new volume on another agent.

### Disk consumption

As of Mesos 0.28, destroying a persistent volume will not cleanup or destroy data. Mesos will delete metadata about the volume in question, but the data will remain on disk. To prevent disk consumption, you should manually remove data when you no longer need it.

### <a name="non-unique-roles"></a>Non-unique Roles

Both static and dynamic reservations in Mesos are bound to roles, not to frameworks or framework instances. Marathon will add labels to claim that resources have been reserved for a combination of `frameworkId` and `taskId`, as noted above. However, these labels do not protect from misuse by other frameworks or old Marathon instances (prior to 1.0). Every Mesos framework that registers for a given role will eventually receive offers containing resources that have been reserved for that role. If such a framework does not respect the presence of labels and the sematic as intended by the creator of these labels, Marathon is unable to reclaim them and cannot act properly. It is therefore recommended to never use the same role for different frameworks if one of them uses dynamic reservations. Marathon instances in HA mode do not need to have unique roles, however, because they use the same role by design.

### The Mesos Sandbox

The temporary Mesos sandbox is still the target for the `stdout` and `stderr` logs. To view these logs, go to the Marathon pane of the DCOS web interface.

### <Missing Multiple Disk Support and it's impact>

## Examples

### Creating a stateful application via the Marathon UI

1. Create a new Marathon application via the web interface.
1. Click the Volumes tab.
1. Choose the size of the volume or volumes you will use. Be sure that you choose a volume size that will fit the needs of your application; you will not be able to modify this size after you launch your application.
1. Specify the container path, from which your application will read and write data. The container path must be non-nested and cannot contain slashes e.g. `data`, but not  `../../../etc/opt` or `/user/data/`.
1. Click Create.

### Running stateful PostgreSQL on Marathon

An exemplary app configuration for PostgreSQL on Marathon would look like this. Note that we set the postgres data folder to `pgdata` (relative to the Mesos sandbox as in `$MESOS_SANDBOX`) so that we can setup a persistent volume with a containerPath of `pgdata`, which is not nested and relative to the sandbox as required:

```
{
  "id": "/postgres",
  "cpus": 1,
  "instances": 1,
  "mem": 512,
  "container": {
    "type": "DOCKER",
    "volumes": [
      {
        "containerPath": "pgdata",
        "mode": "RW",
        "persistent": {
          "size": 100
        }
      }
    ],
    "docker": {
      "image": "postgres:latest",
      "network": "BRIDGE",
      "portMappings": [
        {
          "containerPort": 5432,
          "hostPort": 0,
          "protocol": "tcp",
          "name": "postgres"
        }
      ]
    }
  },
  "env": {
    "POSTGRES_PASSWORD": "password",
    "PGDATA": "pgdata"
  },
  "residency": {
    "taskLostBehavior": "WAIT_FOREVER"
  },
  "upgradeStrategy": {
    "maximumOverCapacity": 0,
    "minimumHealthCapacity": 0
  }
}
```

### Running stateful MySQL on Marathon
The default MySQL docker image does not allow to change the data folder. Since we cannot define a persistent volume with an absolute nested `containerPath` like `/var/lib/mysql`, we need to configure a little workaround that will setup a docker mount from hostPath `mysql` (relative to the Mesos sandbox) to `/var/lib/mysql` (the path that MySQL attempts to read/write):
```
{
  "containerPath": "/var/lib/mysql",
  "hostPath": "mysqldata",
  "mode": "RW"
}
```

In addition to that, we configure a persistent volume with a containerPath `mysql`, which will mount the local persistent volume as `mysql` into the docker container:

```
{
  "containerPath": "mysqldata",
  "mode": "RW",
  "persistent": {
    "size": 1000
  }
}
```

The complete JSON configuration reads as follows:

```
{
  "id": "/mysql",
  "cpus": 1,
  "mem": 512,
  "disk": 0,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "volumes": [
      {
        "containerPath": "mysqldata",
        "mode": "RW",
        "persistent": {
          "size": 1000
        }
      },
      {
        "containerPath": "/var/lib/mysql",
        "hostPath": "mysqldata",
        "mode": "RW"
      }
    ],
    "docker": {
      "image": "mysql",
      "network": "BRIDGE",
      "portMappings": [
        {
          "containerPort": 3306,
          "hostPort": 0,
          "servicePort": 10000,
          "protocol": "tcp"
        }
      ],
      "forcePullImage": false
    }
  },
  "env": {
    "MYSQL_USER": "wordpress",
    "MYSQL_PASSWORD": "secret",
    "MYSQL_ROOT_PASSWORD": "supersecret",
    "MYSQL_DATABASE": "wordpress"
  },
  "upgradeStrategy": {
    "minimumHealthCapacity": 0,
    "maximumOverCapacity": 0
  }
}
```

### Inspecting and deleting suspended stateful tasks

In order to destroy and cleanup persistent volumes and free the reserved resources associated with a task, you need to perform 2 steps:

1. Locate the agent containing the persistent volume and remove the data inside it
1. send an http delete request to Marathon including the `wipe=true` flag

To locate the agent, inspect the Marathon UI and checkout the detached volumes on the _Volumes_ tab, or query the `/v2/apps` endpoint which provides information about the `host` and Mesos `slaveId`

```
$ http GET http://dcos/service/marathon-jar/v2/apps/postgres/tasks

response:

{
  "appId": "/postgres", 
  "host": "10.0.0.168", 
  "id": "postgres.53ab8733-fd96-11e5-8e70-76a1c19f8c3d", 
  "localVolumes": [
    {
      "containerPath": "pgdata", 
      "persistenceId": "postgres#pgdata#53ab8732-fd96-11e5-8e70-76a1c19f8c3d"
    }
  ], 
  "slaveId": "d935ca7e-e29d-4503-94e7-25fe9f16847c-S1"
}
```

_Note_: A running task will show `stagedAt`, `startedAt` and `version` in addition to the above listed information.

You can then

1. remove the data on disk by `ssh'ing` into the agent and doing an `rm -rf <volume-path>/*`
<!-- provide snippets -->

1. delete the task with `wipe=true`, which will expunge the task information from the Marathon internal repository and eventually destroy and unreserve volumes and resources, respectively:
```
http DELETE http://dcos/service/marathon/v2/apps/postgres/tasks/postgres.53ab8733-fd96-11e5-8e70-76a1c19f8c3d?wipe=true
```

### View the Status of Your Application with Persistent Local Volumes

After you have created your Marathon application, click on the _Volumes_ tab of the application detail view to get detailed information about your app instances and associated volumes.

The Status column tells you if your app instance is attached to the volume or not. The app instance will read as “detached” if you have scaled down your application. Currently the only Operation Type available is read/write (RW).

Clicking on the individual volume brings you to the Volume Detail Page, where you can see information about the individual volume.
