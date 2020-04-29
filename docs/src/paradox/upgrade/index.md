---
title: Upgrading to a Newer Version
---

# Upgrading to a Newer Version

We generally recommend creating a backup of the ZooKeeper state before upgrading to be able to downgrade in case of problems after an upgrade. This can be done by creating a copy of ZooKeeper's [data directory](http://zookeeper.apache.org/doc/r3.1.2/zookeeperAdmin.html#The+Data+Directory).

## Upgrading a non HA installation

Upgrading to a newer version of Marathon should be executed in the following order:

1. Tear down the running instance of Marathon.
1. Install the new version of Marathon.
1. Start the new version of Marathon and watch the log for a successful start.  

## Upgrading an HA installation

Upgrading to a newer version of Marathon should be executed in the following order:

1. Tear down all running instances of Marathon except one. This instance will be the leader.
1. Install the new version of Marathon on one of the nodes with the old version.
1. Start the instance with the new version of Marathon.
1. Stop the last node with the old version. Now the new version of Marathon will take over leadership and becomes active.
1. Watch the log of this instance for a successful start. There should be no ERROR or FATAL statements in the logs.
1. Install the new version of Marathon on all remaining nodes with the old version.
1. Start all other instances of Marathon to build a quorum.

## Releases

It is recommended not to skip Marathon releases when upgrading Marathon. For instance, if upgrading to 1.6.x from 1.4.x, you should upgrade to 1.5.x first, and only then to 1.6.x.

For breaking changes and deprecated features in the released versions please refer to the [change log](https://github.com/mesosphere/marathon/blob/master/changelog.md).
