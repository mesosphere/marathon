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


## Upgrading to 0.13

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.13.0

Tasks keys and storage format in ZooKeeper changed in a backward incompatible fashion.
Zookeeper compression is implemented and enabled by default. Older versions will not be able to read compressed entities.
Marathon nowuses logback as logging backend. If you are using custom log4j properties, you will have to migrate them to a logback configuration. 

## Upgrading to 0.11

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.11.0

Java 8 or higher is needed to run Marathon, since Java 6 and 7 support has reached end of life.
`--revive_offers_for_new_apps` is now the default. 
If you want to avoid resetting filters if new tasks need to be started, you can disable this by `--disable_revive_offers_for_new_apps`.

## Upgrading to 0.10

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.10.0
Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.9.0
Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.8.0

0.8, 0.9 and 0.10 only add new optional fields and do not change the storage format in an incompatible fashion.
Thus, an upgrade should not require any migration. You can also rollback at any time in case of errors as long as you
do not start using new features. Nevertheless we always recommend a backup of the Zookeeper state.

## Upgrading from 0.6 to 0.7

Be aware that
downgrading from versions >= 0.7.0 to older versions is not possible
because of incompatible changes in the data format.
[See here]({{ site.baseurl }}/docs/upgrade/06xto070.html) for an upgrade guide from 0.6.* to 0.7.0
