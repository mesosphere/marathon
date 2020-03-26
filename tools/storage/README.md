# Marathon Storage Tool

## About

For an overview, watch the video!

https://www.youtube.com/watch?v=laBSp6VttzI

Some help documentation is shown at launch. This can be shown again with `help`

The tool launches with a fully functional Ammonite REPL, with all of the Marathon libraries loaded and available. An instance of StorageModule is presented as `module`.

The storage tool is not automatically published. You can see a list of all published versions of the tool at [dockerhub](https://hub.docker.com/r/mesosphere/marathon-storage-tool/tags/). If you need a version which is not published, you can easily build it yourself.

### Launching with a permissive DC/OS Cluster

The Marathon storage tool supports all of the parameters that Marathon accepts for storage. For a permissive DC/OS cluster running DC/OS 1.9.4, you would launch it like this:

```
docker run --rm -it mesosphere/marathon-storage-tool:1.4.5 --zk zk://zk-1.zk,zk-2.zk,zk-3.zk:2181/marathon
```

Substitute 1.4.5 in the above command with the version of Marathon running. If the tool detects you are attaching to a Marathon cluster state that differs from the tool version, it will show an error and exit.

### Launching with a strict DC/OS Cluster

To run Marathon storage tool in a strict DC/OS cluster, authentication is needed to access the Zookeeper state. Proceed accordingly:

```
sudo -i
source /run/dcos/etc/marathon/zk.env
docker run --rm -it mesosphere/marathon-storage-tool:1.6.332-343e70457 --zk ${MARATHON_ZK}
```

As usual, replace the storage tool version with the version of Marathon in use by the version of DC/OS running in your cluster.

## Building

See [BUILDING.md](BUILDING.md).
