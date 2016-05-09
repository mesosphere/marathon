---
title: High Availability
---

# High Availability Mode

## Introduction

Marathon supports a high availability mode of operation by default. High availability mode allows applications to continue running if an individual instance becomes unavailable. This is accomplished by running several Marathon instances that point to the same ZooKeeper quorum. ZooKeeper is used to perform leader election in the event that the currently leading Marathon instance fails.

## Configuration

Marathon runs in HA mode by default. To disable HA mode, use the `--disable_ha` command line argument.

Each Marathon instance must be launched with the same ZooKeeper quorum specified. For example, if your quorum is `zk://1.2.3.4:2181,2.3.4.5:2181,3.4.5.6:2181/marathon` then launch each instance in the normal way with the argument:

```sh
--zk zk://1.2.3.4:2181,2.3.4.5:2181,3.4.5.6:2181/marathon
```

## Proxying

Unlike the Mesos web console, the Marathon web console will not redirect to the currently leading Marathon instance. However, it will proxy requests, so the data shown in the web console will be the currently running state of launched applications.
The same is true of requests to Marathon's REST API.
