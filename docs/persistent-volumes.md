---
title: Stateful Applications Using Persistent Volumes
---

# Stateful Applications Using Persistent Volumes

<div class="alert alert-danger" role="alert">
  <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span> Adapted in Marathon Version 1.0 <br/>
  The Persistent Storage functionality is considered beta, so use this feature at your own risk. We might add, change, or delete any functionality described in this document.
</div>

Marathon applications lose their state when they terminate and are relaunched. In some contexts, for instance, if your application uses MySQL, you’ll want your application to preserve its state. You can create a stateful application by specifying a local persistent volume.

When you specify a local volume or volumes, tasks and their associated data are “pinned” to the node they are first launched on and will be relaunched on that node if they terminate. The resources the application requires are also reserved. Local volumes enable stateful tasks because the task can be restarted without data loss.

## Scaling your app

When you scale your app down, the volumes and resources associated with the destroyed instances are detached. At this point, you may destroy the volumes from the Marathon web interface.

You may wish to leave them in the Detached state, however, if you think you will be scaling your app up again; the data on the volume will still be there. However, since all the resources your application needs are also reserved when a volume is detached, you may wish to destroy detached volumes in order to allow other applications and frameworks to use the resources.

**Note:** If your app is deleted, any associated volumes will also be deleted.

## Create an Application with Persistent Local Volumes

1. Create a new Marathon application via the web interface.
1. Click the Volumes tab.
1. Choose the size of the volume or volumes you will use. Be sure that you choose a volume size that will fit the needs of your application; you will not be able to modify this size after you launch your application.
1. Specify the container path, from which your application will read and write data. The container path must be non-nested and cannot contain slashes e.g. `data`, but not  `../../../etc/opt` or `/user/data/`.
1. Click Create.

**Note:** After you launch your application, you will not be able to change the specifications for your local persistent volumes.

Alternatively, you can configure your application to use persistent local volumes in your application definition JSON file:

<pre>
```
{
  "id": "/sesame/samson",
  "residency": {
    "relaunchEscalationTimeoutSeconds": 30,
    "taskLostBehavior": "WAIT_FOREVER"
  },
  "cpus": 0.5,
  "mem": 512.0,
  "disk": 1024.0,
  "instances": 2,
  "container": {
    "type": "MESOS",
    <b>"volumes": [
      {
        "containerPath": "data",
        "mode": "RW",
        "persistent": {
          "size": 10
        } </b>
      }
    ]
  }
}
```
</pre>

## View the Status of Your Application with Persistent Local Volumes

After you have created your Marathon application, click on the “Volumes” tab of the application detail view to get detailed information about your app instances and associated volumes.

The Status column tells you if your app instance is attached to the volume or not. The app instance will read as “detached” if you have scaled down your application. Currently the only Operation Type available is read/write (RW).

Clicking on the individual volume brings you to the Volume Detail Page, where you can see information about the individual volume.

## The Mesos Sandbox

While persistent local volumes allow your application to read and write data to a particular node or nodes, the temporary Mesos sandbox is still the target for the `stdout` and `stderr` logs. To view these logs, go to the Marathon pane of the DCOS web interface.

## Potential Pitfalls

### Replication and Backups

Because tasks are pinned to nodes, your data will be lost if the node is lost. This is the  case when the Mesos agent is disconnected from the master and does not re-register within a certain timeframe, probably due to a network partition or the agent host crashing. If you need to guard against data loss, you must set up replication and backups. 

### Non-unique Roles

In Mesos, volumes are created for *roles*, not for frameworks. If more than one framework is registered with a particular role, the resource offer meant for Marathon may be accepted by another framework. This can result in volume creation failure. Make sure you register Marathon with a unique role that is not used by another framework instance. This also holds true for any other Marathon instance you are running Multiple. Marathon instances in HA mode do not need to have unique roles, however, because they use the same role by design.

For more information, see the [Apache Mesos documentation on persistent volumes](http://mesos.apache.org/documentation/latest/persistent-volume/).
