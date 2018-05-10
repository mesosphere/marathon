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

# Upgrading to 1.6

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v1.6.352

The recommended Mesos version is 1.5.0 or later.

## Breaking changes

- **SentryAppender**.
The Sentry Raven log appender has been updated to version 8.0.x. Users that have enabled the Sentry Raven appender will need to update their configuration according to the [sentry migration guide](https://docs.sentry.io/clients/java/migration/).

- **Scala 2.12**.
Marathon 1.6 is compiled using Scala 2.12. This means that plugins which were compiled using Scala 2.11 will make Marathon fail during startup due to binary incompatibility between Scala 2.11 and 2.12.

## Deprecations

- **/v2/schema route is Deprecated**.
The /v2/schema route, and JSON Schema definitions, are deprecated in favor of RAML. They will not be kept up-to-date. The endpoint will be disabled in Marathon 1.7.0.

For further details please refer to the changelog.

# Upgrading to 1.5

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v1.5.0

The recommended Mesos version is 1.3.0 or later. Upgrade to Marathon 1.5.x can be performed only from 1.4.x.

## Breaking Changes

- **Packaging standardized**.
We now publish more normalized packages that attempt to follow Linux Standard Base Guidelines and use sbt-native-packager to achieve this. Please refer to the changelog for further details.

- **App JSON Fields Changed or Moved**.
Marathon will continue to *accept* the app JSON as it did in 1.4; however, applications that use deprecated fields will be normalized into a canonical representation. Please refer to the changelog for further details.

- **Metric Names Changed or Moved**.
We moved to a different Metrics library and the metrics are not _always_ compatible or the same as existing metrics. Please refer to the changelog for further details.

- **Artifact store has been removed**.
The artifact store was deprecated with Marathon 1.4 and is removed in version. The command line flag `--artifact_store` will throw an error if specified. The REST API endpoint `/v2/artifacts` has been removed completely.

- **Logging endpoint**.
Marathon has the ability to view and change log level configuration during runtime via the `/logging` endpoint. This version switches from a form based API to a JSON based API, while maintaining the functionality.

- **Event Subscribers has been removed**.
The events subscribers endpoint (`/v2/eventSubscribers`) was deprecated in Marathon 1.4 and is removed in this version. Please move to the `/v2/events` endpoint instead.

- **Removed command line parameters**.
The command line flag `max_tasks_per_offer` has been deprecated since 1.4 and is removed now. Please use `max_instances_per_offer`.

- **Deprecated command line parameters**.
The command line flag `save_tasks_to_launch_timeout` is deprecated and has no effect any longer.

For further details please refer to the changelog.

# Upgrading to 1.4

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v1.4.0

## Breaking Changes

- **You need Mesos 1.1.0 or higher**.
Starting with Marathon 1.4.0, Mesos 1.1.0 or later is required.

- **Plugin API has changed**.
In order to support the nature of pods, we had to change the plugin interfaces in a backward incompatible fashion. Plugin writers need to update plugins, in order to use this version.

- **Health reporting via the event stream**.
Adding support for pods in Marathon required the internal representation of tasks to be migrated to instances. An instance represents the executor on the Mesos side, and contains a list of tasks. This change is reflected in various parts of the API, which now accordingly reports health status etc for instances, not for tasks.

## Important changes

- **New ZK persistent storage layout**.
ZooKeeper has a limitation on the number of nodes it can store in a directory node. Marathon used a flat storage layout in ZooKeeper and encountered this limitation with large installations. Now Marathon uses a nested storage layout, which significantly increases the number of nodes that can be stored. A migration inside Marathon automatically migrates the prior layout to the new one.

- **Improve Deployment logic**.
During Marathon master failover all deployments are started from the beginning. This can be cumbersome if you have long-running updates and a Marathon failover. This version of Marathon reconciles the state of a deployment after a failover. A running deployment will be continued on the new elected leader without restarting the deployment.

## Deprecations

- **Marathon-based Health Checks**.
Mesos now supports command-based as well as network-based health checks. Since those health check types are now also available in Marathon, the Marathon-based health checks are now deprecated.

- **Event Callback Subscriptions**.
Marathon has two ways to subscribe to the internal event bus: a) HTTP callback events managed via `/v2/eventSubscriptions` and b) Server Send Events via `/v2/events` (since Marathon 0.9). We encourage everyone to use the `/v2/events` SSE stream instead of HTTP Callback listeners.

- **Artifact Store**.
The artifact store was introduced as an easy solution to store and retrieve artifacts and make them available in the cluster. There is a variety of tools that can handle this functionality better then Marathon. We will remove this functionality from Marathon without replacement.

- **PATCH semantic for PUT on /v2/apps**.
A PUT on /v2/apps has a PATCH like semantic:a ll values that are not defined in the json, will not update existing values. This was always the default behaviour in Marathon versions. For backward compatibility, we will not change this behaviour, but let users opt in for a proper PUT. The next version of Marathon will use PATCH and PUT as two separate actions.

- **Forcefully stop a deployment**.
Deployments in Marathon can be stopped with force. All actions currently being performed in Marathon will be stopped; the state will not change. This can lead to an inconsistent state and is dangerous. We will remove this functionality without replacement.

- **Command line parameters**.
  - Removed the deprecated `marathon_store_timeout` command line parameter. It was deprecated since v0.12 and unused.
  - Mark `task_lost_expunge_gc` as deprecated, since it is not used any longer
  - The command line flag `max_tasks_per_offer` is deprecated. Please use `max_instances_per_offer`.
  - The deprecated command line flag `enable_metrics` is removed. Please use the toggle `metrics` and `disable_metrics`
  - The deprecated command line flag `enable_tracing` is removed. Please use the toggle `tracing` and `disable_tracing`

For further details please refer to the changelog.

## Upgrading to 1.3

### Marathon 1.2 Skipped in Favor of Marathon 1.3
We have been focusing our efforts on two big new features for the upcoming DC/OS v1.8 release and had to work around the feature freeze in the Marathon v1.2 release candidates. Therefore, we discontinued work on the v1.2 release in favor of a new Marathon v1.3 release candidate

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v1.3.0

### Breaking Changes

- **You need Mesos 1.0.0 or higher**
Starting with Marathon 1.3.0, Mesos 1.0.0 or later is required.

- **New leader election library**
The leader election code has been greatly improved and is based on Curator,a well-known, well-tested library. The new leader election functionality is now more robust.

**Caution:** The new leader election library is not compatible with Marathon versions prior to 1.3.0. To upgrade to this version, stop all older Marathon instances before the new version starts. Otherwise, there is a risk of more than one leading master.

- **Framework authentication command line flags**
Prior versions of Marathon have tried to authenticate whenever a principal has been provided via the command line.
Framework authentication is now explicit. There is a command line toggle option for authentication: --mesos_authentication.
This toggle is disabled by default. You must now supply this flag to use framework authentication.

- **Changed default values for TASK_LOST GC timeout**
If a task is declared lost in Mesos, but the reason indicates it might come back, Marathon waits for the task to come back for a certain amount of time.
To configure the behavior you can use --task_lost_expunge_gc, --task_lost_expunge_initial_delay, --task_lost_expunge_interval.
Until version 1.3 Marathon has handled TASK_LOST very conservatively: it waits for 24 hours for every task to come back.
This version reduces the timeout to 75 seconds (task_lost_expunge_gc), while checking every 30 seconds (task_lost_expunge_interval).


## Upgrading to 0.13

Release Notes: https://github.com/mesosphere/marathon/releases/tag/v0.13.0

Tasks keys and storage format in ZooKeeper changed in a backward incompatible fashion.
Zookeeper compression is implemented and enabled by default. Older versions will not be able to read compressed entities.
Marathon now uses logback as logging backend. If you are using custom log4j properties, you will have to migrate them to a logback configuration.

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
[See here]({{ site.baseurl }}/1.4/docs/upgrade/06xto070.html) for an upgrade guide from 0.6.* to 0.7.0
