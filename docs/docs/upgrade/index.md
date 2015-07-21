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
1. Start all other instances of Marathon instances to build a quorum.


## Upgrading from 0.7.* or 0.8.* to 0.9.*

0.8.x and 0.9.x only add new optional fields and do not change the storage format in an incompatible fashion.
Thus, an upgrade should not require any migration. You can also rollback at any time in case of errors as long as you
do not start using new features. Nevertheless we always recommend a backup of the Zookeeper state.

## Upgrading from 0.6.* to 0.7.0

Be aware that
downgrading from versions >= 0.7.0 to older versions is not possible
because of incompatible changes in the data format.
[See here]({{ site.baseurl }}/docs/upgrade/06xto070.html) for an upgrade guide from 0.6.* to 0.7.0
