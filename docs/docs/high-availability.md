---
title: High Availability
---


# Introduction

Marathon by default supports a high availability mode of operation which allows applications to continue running if an individual instance becomes unavailable. This is accomplished by running several Marathon instances pointing to the same ZooKeeper quorum. ZooKeeper is used to perform leader election in the event that the currently leading Marathon instance fails.


# Configuration

When set to `true`, the `--ha` command line argument launches Marathon in high availability mode. `true` is the default, so it does not need to explicitly be set.

Each Marathon must be launched with the same ZooKeeper quorum specified. For example, if your quorum is `zk://1.2.3.4:2181,2.3.4.5:2181,3.4.5.6:2181/marathon` then launch each instance in the normal way with the argument:

```sh
--zk zk://1.2.3.4:2181,2.3.4.5:2181,3.4.5.6:2181/marathon
```

# Proxying

Unlike the Mesos web console, the Marathon web console will not redirect to the currently leading Marathon instance. However, it will proxy requests, so the data shown in the web console will be the currently running state of launched applications.
The same is true of requests to Marathon's REST API.