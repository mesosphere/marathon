# Framework ID registration
Marathon registers as a framework with Mesos. During the first attempt to connect with Mesos, Marathon requests a new framework ID. This framework ID is used to associate the instance of Marathon with the tasks launched by that instance of Marathon.

It is very important to consistently use the same framework ID. If Marathon changes framework IDs while existing tasks are running, then the old tasks will become effectively orphaned, while a new set of tasks will be launched. Because of this, Marathon will refuse to generate a new framework ID if there are any instances specified. This means that Marathon may not be able to launch under the following cases:

* The Zookeeper record containing the Marathon framework ID was removed, is inaccessible, or was corrupted.
* The framework ID associated with the Marathon instance was marked as "torn down" in Mesos, or the framework failover timeout has been exceeded; as a result Marathon cannot reuse its framework ID.

## Recovering from Framework ID issues

To recover from this, you can use the [Marathon Storage Tool](https://github.com/mesosphere/marathon-storage-tool) to repair / update Marathon's state. Follow the instructions in the README to launch the appropriate version for your Marathon instance, and to provide the appropriate configuration flags.

# Zookeeper record containing the Marathon framework ID was removed/inaccessible/corrupted

To repair the Marathon's framework ID record in Zookeeper, you will need the framework ID as which Marathon should connect. You can get this by accessing the frameworks section of the Mesos UI, or by requesting the framework information JSON state from `{mesos_url}/frameworks`.

Launch the Marathon Storage Tool and update the framework ID using the following commands (where `00000000-0000-0000-0000-000000000000-0000` is your framework ID):

```
import mesosphere.util.state.FrameworkId

module.frameworkIdRepository.store(
  FrameworkId("00000000-0000-0000-0000-000000000000-0000"))
```

It is critical that you specify the framework ID exactly as it is registered in Mesos. Be sure that there are no spaces in the ID.

# Marathon Framework was torn down in Mesos

In this case, Mesos is marked a framework as gone, and a new framework ID will need to be generated. Since Marathon will refuse to generate a new framework ID if instances are defined, you will need to delete all instances, in addition to deleting the framework ID record. All service state (apps, pods, groups, etc.) will be preserved.

```
purge(listInstances())

module.frameworkIdRepository.delete()
```

Quit the Marathon Storage Tool, and restart Marathon. Marathon will generate a new framework ID on launch.

Note that old reservations for persistent tasks launched by the old framework ID will not be reused, nor will they be cleaned up. You'll need to manually destroy these reservations using the Mesos API.
