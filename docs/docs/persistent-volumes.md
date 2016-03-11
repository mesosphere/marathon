---
title: Stateful Applications Using Local Persistent Volumes
---

Marathon applications lose their state when they terminate and are relaunched. In some contexts, for instance, if your application uses MySQL, you’ll want your application to preserve its state. You can create a stateful application by specifying a local persistent volume.

When you specify a local volume or volumes, tasks and their associated data are “pinned” to the node they are first launched on and will be relaunched on that node if they terminate. Local volumes enable stateful tasks because the task can be restarted without data loss.

## Scaling your app

When you scale your app instances/tasks down, the volumes associated with the destroyed tasks/app instances are detached. At this point, you may destroy the volume from the Marathon web interface. You may wish to leave it in the Detached state, however, if you think you will be scaling up your app again; the data on the volume will still be there. **Note:** If your app is deleted, any associated volumes will also be deleted.

## Create an Application with Persistent Local Volumes

1. Create a new Marathon application via the web interface.
1. Click the Volumes tab.
1. Choose the size of the volume or volumes you will use. Be sure that you choose a volume size that will fit the needs of your application; you will not be able to modify this size after you launch your application. <!-- note, I need to actually do this and see how you can choose more than one volume -->
1. Specify the container path, from which your application will read and write data. The container path must be non-nested, e.g. `/var/lib/mysql`, but not  `../../../etc/opt`.
1. Click Create.

**Note:** After you launch your application, you will not be able to change the specifications for your local persistent volumes.

<!--
<screen shot>
-->

Alternatively, you can configure your application to use persistent local volumes in your application definition `config.json` file:

```
{
  "id": "/sesame/samson",
  "residency": {
    "relaunchEscalationTimeoutSeconds": 30,
    "taskLostBehavior": "relaunchAfterTimeout"
  },
  "cpus": 0.5,
  "mem": 512.0,
  "disk": 1024.0, // sandbox disk space
  "instances": 2,
  "container": {
    "type": "MESOS",
    "volumes": [
      { // --> This will create a persistent volume
        "containerPath": "/local/a",
        "mode": "RW",
        "persistent": {
          "size": 1234
        }
      }
    ]
  }
}
```

## View the Status of Your Application with Persistent Local Volumes

After you have created your Marathon application, click on the “Volumes” tab of the application detail view [check that vocab] to get detailed information about your tasks/app instances and associated volumes.

The Status column tells you if your task/app instance is attached to the volume or not. The task/app instance will read as “detached” if you have scaled down your application. Currently the only Operation Type available is read/write (RW).

<!--
<screen shot>
-->

Clicking on the individual volume brings you to the Volume Detail Page, where you can see information about the individual volume:

<!--
<screen shot>
-->

## The Mesos Sandbox

While persistent local volumes allow your application to read and write data to a particular node or nodes, the temporary Mesos sandbox is still the target for the `stout` and `standerr` logs. To view these logs, go to the Marathon pane of the DCOS web interface.

## Potential Pitfalls

### Replication and Backups

Because tasks are pinned to nodes, your data can be lost if the node is lost. If you need to guard against data loss, you must set up replication and backups. 

### Non-unique Roles

In Mesos, volumes are created for *roles,* not for frameworks. If more than one framework is registered with a particular role, the resource offer meant for Marathon may be accepted by another framework. This can result in volume creation failure. For more information, see the [Apache Mesos documentation on persistent volumes](http://mesos.apache.org/documentation/latest/persistent-volume/).







