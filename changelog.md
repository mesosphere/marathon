## Changes from 0.11.0 to 0.12.0

### Fixed Issues

- \#2294 - Make boolean command line flags use Scallop's 'toggle'
- \#2249 - Update delay before killing old tasks

## Changes from 0.10.0 to 0.11.0

### Breaking Changes

* Java 8 or higher is needed to run Marathon, since Java 6 and 7 support has reached end of life.
* `--revive_offers_for_new_apps` is now the default. If you want to avoid resetting filters
  if new tasks need to be started, you can disable this by `--disable_revive_offers_for_new_apps`.

### Overview

#### Marathon uses Java 8

Java 8 has been out for more than a year now. The support for older versions of Java has reached end of life.
We switched completely to the latest stable JVM release.
We compile, test and run Marathon against Java 8.
Note for the Marathon Docker image: the base image of Marathon is now the standard java:8-jdk docker image.

#### Faster reconciliation and links to task Mesos sandboxes

Marathon now saves the slaveID of newly launched tasks.
That leads to faster reconciliation for lost tasks and allows the UI to link to the mesos UI.

If the auto-discovered mesos UI is not reachable from wherever you access the Marathon UI from, you can
use the `--mesos_leader_ui_url` configuration to change the base URL of these links.

#### New versioning information in API and UI

We now have `"versionInfo"` with `"lastConfigChangeAt"` and `"lastScalingAt"` in the apps JSON of our API.
`"lastConfigChangeAt"` is the timestamp of the last change to the application that was not just a restart or
a scaling operation. `"lastScalingAt"` is the time stamp of the last scaling or restart operation.

##### Additional task statistics in the `/v2/apps` and `/v2/apps/{app id}` endpoints (Optional)

If you pass `embed=apps.taskStats`/`embed=app.taskStats` as a query parameter, you get additional taskStats
embedded in you app JSON.

Task statistics are provided for the following groups of tasks. If no tasks for the group exist, no statistics are
offered:

* `"withLatestConfig"` contains statistics about all tasks that run with the same config as the latest app version.
* `"startedAfterLastScaling"` contains statistics about all tasks that were started after the last scaling or
  restart operation.
* `"withOutdatedConfig"` contains statistics about all tasks that were started before the last config change which
  was not simply a restart or scaling operation.
* `"totalSummary"` contains statistics about all tasks.

Example JSON:

```javascript
{
  // ...
  "taskStats": {
    {
      "startedAfterLastScaling" : {
        "stats" : {
          "counts" : { // equivalent to tasksStaged, tasksRunning, tasksHealthy, tasksUnhealthy
            "staged" : 1,
            "running" : 100,
            "healthy" : 90,
            "unhealthy" : 4
          },
          // "lifeTime" is only included if there are running tasks.
          "lifeTime" : {
            // Measured from `"startedAt"` (timestamp of the Mesos TASK_RUNNING status update) of each running task
            // until now.
            "averageSeconds" : 20.0,
            "medianSeconds" : 10.0
          }
        }
      },
      "withLatestConfig" : {
        "stats" : { /* ... same structure as above ... */ }
      },
      "withOutdatedConfig" : {
        "stats" : { /* ... same structure as above ... */ }
      },
      "totalSummary" : {
        "stats" : { /* ... same structure as above ... */ }
      }
    }
  }
  // ...
}
```

The calculation of these statistics is currently performed for every request and expensive if you have
very many tasks.

#### Specific "embed" parameters for app related information

In this release, we introduce "embed" parameters for all GET requests in the
app end-point that specify which information to embed. Right now, we deliver all
information by default that we delivered before but we encourage you to specify
embed parameters for the information that you need.

In the future, we will only
deliver information you explicitly requested by default, even though we will
allow some compatibility configuration option. This improves performance by not
returning information that you might not need.

#### Smarter resource offer handling

When Marathon needed to launch tasks for multiple apps, it would first launch all needed tasks for the first one
and then proceed to the next app. One big deployment could block all others. In this new version, Marathon
will spread the offers among all apps that currently need to launch tasks.

When Marathon detects new tasks that it has to launch, it will now explicitly request new offers
with the `reviveOffers` Mesos scheduler API call. This should result in more speedy task launches. If
for any reason you dislike this behavior, you can disable it with `--disable_revive_offers_for_new_apps`
(not recommended).

The order in which mesos receives `reviveOffers` and `declineOffer` calls is not guaranteed. Therefore, as
long as we still need offers to launch tasks, we repeat the `reviveOffers` call for `--revive_offers_repetitions`
times so that our last `reviveOffers` will be received after all relevant `declineOffer` calls with high
probability.

* `--revive_offers_repetitions` (Optional. Default: 3):
    Repeat every reviveOffer request this many times, delayed by the `--min_revive_offers_interval`.

When Marathon has no current use for an offer, it will decline the offer for a configurable period. A short duration
might lead to resource starvation for other frameworks if you run many frameworks
in your cluster. You should only need to reduce it if you use `--disable_revive_offers_for_new_apps`.

* `--decline_offer_duration` (Default: 120 seconds) The duration (milliseconds) for which to decline offers by default

#### New task launching tuning parameters

To prevent overloading Mesos itself, you can now restrict how many tasks Marathon launches per time interval.
By default, we allow 1000 unconfirmed task launches every 30 seconds. In addition, Marathon
considers `TASK_RUNNING` status updates from Mesos as launch confirmations and allows launching one more task
for every confirmed launch.

* `--launch_token_refresh_interval` (Optional. Default: 30000):
    The interval (ms) in which to refresh the launch tokens to `--launch_token_count`.
* `--launch_tokens` (Optional. Default: 1000): Launch tokens per interval.

As a result of this, the `--max_tasks_per_offer_cycle` options is deprecated and has no effect anymore.

To prevent overloading Marathon and maintain speedy offer processing, there is also a timeout for matching each
incoming resource offer, i.e. finding suitable tasks to launch for incoming offers.

* `--offer_matching_timeout` (Optional. Default: 1000):
    Offer matching timeout (ms). Stop trying to match additional tasks for this offer after this time.
    All already matched tasks are launched.

All launched tasks are stored before launching them. There is also a new timeout for this:

* <span class="label label-default">v0.11.0</span> `--save_tasks_to_launch_timeout` (Optional. Default: 3000):
    Timeout (ms) after matching an offer for saving all matched tasks that we are about to launch.
    When reaching the timeout, only the tasks that we could save within the timeout are also launched.
    All other task launches are temporarily rejected and retried later.

If the mesos master fails over or in other unusual circumstances, a launch task request might get lost.
You can configure how long Marathon waits for the first `TASK_STAGING` update.

* <span class="label label-default">v0.11.0</span> `--task_launch_confirm_timeout` (Optional. Default: 10000):
  Time, in milliseconds, to wait for a task to enter the `TASK_STAGING` state before killing it.

#### No pseudo-deterministic assignment of host ports anymore

If you specify non-zero `"ports"` in your app JSON, they are used as service ports. The old code contained logic that
would assign these ports as host ports if available in the
processed offer. That misled people into thinking that these ports corresponded to host ports. The new code always
randomizes host ports assignment without `"requirePorts"` or explicit `"hostPort"` configuration.

#### Last task failures are persisted

Marathon has exposed the `"lastTaskFailure"` via the API for a while but this information was not persisted
across restarts or on fail over. Now it is.

#### Logging command line parameters on startup

Marathon will now log all command line parameters on startup. Example:

```
2015-08-02 11:48:08,047] INFO Starting Marathon 0.11.0-SNAPSHOT with --zk zk://master.mesos:2181/marathon --master master.mesos:5050
```

#### Improved logging of deployment plans

Example:

```
DeploymentPlan 2015-09-02T07:56:12.105Z
step 1:
  * Start(App(app1, cmd="cmd")), instances=0)
step 2:
  * Scale(App(app1, cmd="cmd")), instances=2)
```

#### Improved logging of offer rejections

Example for missing port:

```
[2015-08-04 20:11:20,510] INFO Offer [20150804-173037-16777343-5050-4340-O110]. Cannot find range with host port 8080 for app [/product/frontend]
[2015-08-04 20:11:29,422] INFO Offer [20150804-173037-16777343-5050-4340-O110]. Insufficient resources for [/product/frontend] (need cpus=1.0, mem=64.0, disk=1.0, ports=([8080] required + 1 dynamic), available in offer:
...
```

Example for missing basic resources:

```
[2015-08-04 20:17:27,986] INFO Offer [20150804-173037-16777343-5050-4340-O110]. Not all basic resources satisfied: cpu NOT SATISFIED (30.0 > 8.0), disk SATISFIED (0.0 <= 0.0), mem SATISFIED (16.0 <= 15360.0)
[2015-08-04 20:17:27,989] INFO Offer [20150804-173037-16777343-5050-4340-O110]. Insufficient for [/test] (need cpus=30.0, mem=16.0, disk=0.0, ports=(1 dynamic), available in offer:
...
```

#### Immediately store changed apps

In the past, Marathon would update the data for the group endpoint when it accepted a new deployment request
like a change an app configuration. Marathon would only update the data in the app endpoint when it started
the related deployment step. This could cause confusion for our users and thus we will now update both
immediately when accepting the deployment.

#### No automatic reset of the app backoff when detecting a running task

When encountering task failures, Marathon uses the backoff duration to throttle launching tasks.
See `"backoffSeconds"`, `"backoffFactor"` and `"maxLaunchDelaySeconds"` in the REST API documentation
for further information.

There was an undocumented feature that reset the backoff completely whenever Marathon received a TASK_RUNNING
notification from Mesos. This led to problems when an application crashed fast after startup.

In the new Marathon version, the backoff delay is never reset automatically. You can reset it manually, though.

#### New `MARATHON_APP_DOCKER_IMAGE` environment variable

Any task of an app definition with a docker image attribute (`container.docker.image`) will now be started with
an environment variable `MARATHON_APP_DOCKER_IMAGE` containing its value.

### Important bug fixes

* \#1553 -
  Marathon will now correctly reload task state information after a fail over
* \#1924 -
  Marathon will accept offers without disk resources if no disk resources are required
* \#1671 - Mesos will now use the hostname given
  by the `--hostname` parameter to communicate with Marathon
* \#1926 -
  Our leader proxy code used buffered IO without intermediate flushing which did not play well with
  streaming events from our `/v2/events` endpoint
* \#1877 -
  Marathon will now exit on startup failures instead of keeping running without being able to answer to requests.
  For example: Marathon will now exit if the specified http port is already used for something else.

### Known issues

Applications might not be returned for a little while even after `POST /v2/apps` returned 201. This is
side-effect of storing the tasks information in ZooKeeper and we plan to fix it in the next releases.

### Under the Hood

#### Jetty 9 as Servlet Engine

The latest Jetty servlet engine is used in this version of Marathon.
Jetty 9 has a completely overhauled I/O layer, Servlet API 3.0, SPDY/3 and WebSocket support.

#### Improved SSE handling

As part of the Jetty 9 update, the SSE support for `/v2/events` has been improved.
The event name `event: event-name` is added to every `data: json` entry for easier filtering and handling.

#### Play JSON everywhere

We finished our transition from Jackson JSON serialization to Play JSON. Play JSON provides a type safe
interface which make it easier to write correct code.

### Details for Marathon UI

#### New application modal, including Docker-specific fields

The application modal has undergone significant changes, simplifying the app creation process and giving the
user access to more advanced features. In particular, a section of the form is dedicated to Docker specific
fields, allowing the user to create Dockerized applications directly from the UI.

#### Edit application configurations

Previous versions of Marathon did not allow users to make modifications to application configurations after
the application had been created. It's now possible to edit an application using the same improved modal that
is used to create applications.

#### Usability improvements to the applications view

The applications view is the 'dashboard' for Marathon UI. This version brings significant improvements to its
utility, especially by revealing more about application health.

##### Search bar

Applications can be filtered by name using the search bar in the top left-hand corner of the applications view.

##### Show total resource usage

Previously, only the configured resource usage was shown in the applications view, so an application with 100
running tasks would show the same resource usage as an identical application with only 1 running task. Now, the
combined resource usage is shown, allowing users to sort applications by their total assigned resources.

##### Sort applications by health

Unhealthy apps can be found quickly by sorting the application table by health status.

##### Better progress information feedback

A tooltip is displayed when the user hovers the application progress bar, showing individual health statuses.

#### More information on applications and deployments

In previous versions of Marathon, only an application's configuration and healthcheck status were available
from the UI. Marathon 0.11 brings the following features:

##### Debug app tab

A new tab is available in the application detail view. It displays the most recent changes to the application
configuration, the most recent task failure, and the relevant statistics.

##### Health checks in configuration tab.

Application health checks are now shown in the application configuration tab.

##### Direct Mesos sandbox access

Where available, the new 'Mesos details' link in the task detail view shows a link to the relevant sandbox in
the Mesos UI.

#### Other features

##### User- and debug-friendly version strings

The Marathon version string is now shown in the user's local time. In addition, The UI version string is
available when the user hovers the version string, and in a separate alert when the user hits the 'g v'
shortcut.

##### Custom alerts

Where browser-native dialogs were previously used, Marathon now uses custom dialogs which are consistent with
the UI style and which do not interrupt the UI when in the foreground.

##### Not Found page

When the user follows a bad link, they encounter a Not Found page rather than being redirected to the
applications view.

#### UI build uses a webjar

Instead of relying on git submodules, the UI is now released as a webjar which Marathon pulls in during its
own build. This allows developers to use the latest stable UI assets and simplifies the build process. This
has had the side-effect that the ui is now served from the /ui/ endpoint instead of the root.

#### Fixed issues

- \#548 - UI showing empty list after scaling when on page > 1
  * The task list shows the last available page
    if tasks count decreases after scaling.
- \#1872 - Kill & Scale should be available for more than one task
- \#1960 - Task detail error message doesn't show up on non existent task
- \#1989 - HealthBar isn't working correctly on non existing health data
- \#2014 - Avoid concurrent http requests on same endpoint
- \#1996 - Duplicable fields in app creation modal can send null values
- \#2030 - Shortcut for app creation no longer works
- \#2062 - Resetting app delay can block all network requests in Firefox
- \#2123 - Health check information isn't shown on task in task list
           and task detail

### List of Contributors

Commits | Contributor
-------------|----------------
169 | Felix Gertz
70 |  Philip Norman
50 | Pierluigi Cau
5 | Sp3c1
4 | Kamil Warguła
4 | Daniel Fuentes

Generated by `git shortlog -s -n --no-merges v0.10.0..v0.11.0` for the marathon-ui repository


## Changes from 0.9.0 to 0.10.0

### Recommended Mesos version is 0.22.1

We tested this release against Mesos version 0.22.1. Thus, this is the recommended
Mesos version for this release.

### Notes

This will be the last release that supports Java 6, since Java 6 and Java 7 have reached End of Life.
Starting with 0.11.0 we will rely on Java 8.

### Overview

#### Administratively zeroing the task launch delay

It is now possible to reset the task launch delay via the REST endpoint `/v2/queue/{app_id}/delay`.
The UI displays two additional possible statuses: "Delayed" and "Waiting".
The "Delayed" status also displays a tooltip showing the remaining time until the next launch attempt.
The App page now allows the user to reset the task launch delay for a "Delayed" app, thus forcing a new immediate launch attempt.

#### The FrameworkId is invalidated, if the scheduler reports an error

If the scheduler reports an error, the framework id is removed from the backing persistence store.
This is important because on certain kinds of framework errors (such as exceeding the framework failover timeout),
the scheduler may never re-register with the saved FrameworkID until the leading Mesos master process is killed.

#### Maintain constraints while scaling down

Constraints in Marathon is a powerful mechanism.
While the constraints are maintained when scaling up, previous versions of Marathon did not do so while scaling down.
This version picks instances to kill based on defined constraints and will not violate those.

#### Custom prefix for automatically created environment variables

It is now possible to specify a custom prefix via the `--env_vars_prefix`
command line flag. This prefix will be added to the name of task's environment
variables created automatically by Marathon (e.g., `PORT`, `PORTS`)

_Note_: This prefix will not be added to variables that are already prefixed,
such as `MESOS_TASK_ID` and `MARATHON_APP_ID`

#### Handle requests with missing Accept and Content-Type headers correctly

If an HTTP request is made without setting appropriate request headers, the Accept and Content-Type header
is set automatically to `application/json`.

#### Option to restrict the number of concurrent HTTP requests

With this version of Marathon it is possible to restrict the number of concurrent HTTP requests,
that are handled concurrently in the service layer.
This enables the Marathon service to apply back pressure when receiving too many requests
and helps prevent Denial of Service attacks.
If the limit of concurrent requests is reached, a HTTP 503 Service Temporarily Unavailable is returned,
which means that this operation can be retried.
You can turn on this feature by setting the parameter `--http_max_concurrent_requests`.

#### Serialize all concurrent change requests

In former versions of Marathon it was possible that multiple concurrent changes of the same application definition
would lead to an inconsistent state of that application. All changes of groups or application definitions now
get serialized, so the resulting state is always consistent.

#### Enhance the reported metrics

The metrics endpoint (`/metrics`) gives valuable insight into the system behavior.
We now have meters for all service objects as well as the number applications and groups in the system.

#### Decouple API (un)marshalling types from internal models

With this change we can control API changes and are free to change internal models.
This will change nothing in the current API, but will help a lot while we create the v3 API.

### Infrastructure

#### Added integration tests for all API endpoints

We now have an integration test suite, that covers all endpoints with almost all possible parameter combinations.
This test suite runs with every commit on every pull request.
This test suite is our safety net to ensure compatibility while introducing new functionality in future versions.

#### Code coverage reporting using coveralls

See our github page https://github.com/mesosphere/marathon or go directly to
https://coveralls.io/github/mesosphere/marathon to see our code coverage reporting.
With every Pull Request we try to make sure, this coverage will be increased.

#### Improved the release process of Marathon itself

Several changes are made to the sbt build file, but also to the infrastructure that is performing the release.
We now have a more reliable way of pushing distributions.

#### Improve the way in which unused offers are declined, in order to avoid starvation in a multi-framework context.

We have observed that running a large number of frameworks could lead to starvation, in which some frameworks would not receive any offers from the Mesos master. This release includes changes to mitigate offer starvation.
This is done by making the amount of time for which an offer will be declined configurable. This value defaults to 5 seconds (Mesos default), but should be set to a higher value in a multi-framework environment, in order to reduce starvation.
If there is need for an offer (e.g., when a new app is added), then all already declined offers will be actively revived.
`--decline_offer_duration` allows configuring the duration for which unused offers are declined.
`--revive_offers_for_new_apps` if specified, then revive offers will be called when a new app is added to the TaskQueue
`--min_revive_offers_interval` if `--revive_offers_for_new_apps` is specified, do not call reviveOffers more often than this interval.


### Fixed Issues

- #1710 - Too many simultaneous requests will kill Marathon
- #1709 - Concurrent changes to the same AppDefinition will lead to inconsistent state
- #1669 - Inconsistent content-type and response from REST API
- #1660 - The GUI doesn't allow creating apps with 0 instances, but it is possible through the v2 API
- #1647 - Keep the rest-api return format consistent when request headers without "Accept: application/json"
- #1397 - Kill tasks with scaling does not update group
- #1654 - Marathon 0.8.1 - API - /v2/tasks
- #555  - Constraints not satisfied when scaling down
- #650  - deployment_success/failed should contain deployment info
- #1316 - Invalidate framework ID if registration triggers an error
- #1254 - Add flag to define Marathon PORT prefix
- #1853 - App can't be deleted because it "does not exist", although /apps still returns it
- #1924 - Launch tasks that require no disk resource
- #1927 - Improve logging of unsatisfied resource requirements
- #1931 - Flag for reviveOffers and the duration for which to reject offers

------------------------------------------------------------


## Changes from 0.8.2 to 0.9.0

#### Recommended Mesos version is 0.22.1

We tested this release against Mesos version 0.22.1. Thus, this is the recommended
Mesos version for this release.

### Breaking changes

Please look at the following changes to check whether you have to verify your setup before upgrading:

* Disk resource limits are passed to Mesos.
* New format for the `http_endpoints` command line parameter.
* New default for the `zk_max_versions` command line parameter. In Marathon, most state is versioned. This includes app
  definitions and group definitions. Marathon already allowed restricting the number of versions that are kept by
  `--zk_max_versions` but you had to specify that explicitly.

  Starting with this version, Marathon will by default keep only 25 versions. That means that Marathon will start to
  remove old versions in order to enforce the limit.
* Removed the deprecated `zk_hosts` and `zk_state` command line parameters. Use the `zk` parameter instead.


### Overview

#### Restrict applications to certain Mesos roles

Prior Marathon versions already support registering with a `--mesos_role`. This causes Mesos to offer resources
of the specified role to Marathon in addition to resources without any role designation ("*").
Marathon would use resources of any role for tasks of any app.

Now you can specify which roles Marathon should consider for launching apps per default via the
`--default_accepted_resource_roles` configuration argument. You can override the default by specifying
a list of accepted roles for your app via the `"acceptedResourceRoles"` attribute of the app definition.

#### Event stream as server sent events

Prior Marathon versions already notified other services of events via
[event subscriptions](https://mesosphere.github.io/marathon/docs/rest-api.html#event-subscriptions).
Services could register an HTTP endpoint at which they received all events.

The new Marathon now provides an
[event stream](https://mesosphere.github.io/marathon/docs/rest-api.html#event-stream) endpoint
where you receive all events conveniently as
[Server Sent Events](http://www.w3schools.com/html/html5_serversentevents.asp).

#### Abstraction for persistent storage added with ZooKeeper access directly in the JVM

A new storage abstraction has been created, which allows for different storage providers. It is completely non-blocking
and provides consistent usage patterns.

The new ZooKeeper Storage Provider is implemented in a backward compatible fashion - the same data format and storage
layout is used as prior versions of Marathon.

You can use this version of Marathon without migrating data while it is also possible to switch back to an older
version. The new persistent storage layer is enabled by default, no further action is needed.


#### Satisfy ports from any offered port range

In prior Marathon versions, matching port resources to the demands of a task had various restrictions:

* Marathon could not launch a task if it required port resources with different Mesos roles.
* Dynamically assigned non-docker host ports had to come from a single port range.

Now the port resources of a task can be satisfied by any combination of port ranges with any matching offered
role.

#### Randomize dynamic docker host ports

If a task reuses recently freed port resources, it can happen that dependencies of old tasks still expect
the old task to be reachable at the old port for a limited time span. For this reason, Marathon has already
randomized assignment of dynamic non-docker host ports to minimize the risk of launching a new task on ports recently
used by other tasks.

Now Marathon also randomly assigns dynamic docker host ports.

#### Disk resource limits are passed to Mesos

If you specify a non-zero disk resource limit, this limit is now passed to Mesos on task launch.

If you rely on disk limits, you also need to configure Mesos appropriately. This includes configuring the correct
isolator and enabling disk quotas enforcement with `--enforce_container_disk_quota`.

#### Improved proxying to current leader

One of the Marathon instances is always elected as a leader and is the only instance processing your requests.
For convenience, Marathon has long proxied all requests to non-leaders to the current leader so that
you do not have to lookup the current leader yourself or are annoyed by redirects.

This proxying has now been improved and gained additional configuration parameters:

* `--leader_proxy_connection_timeout` (Optional. Default: 5000):
    Maximum time, in milliseconds, for connecting to the
    current Marathon leader from this Marathon instance.
* `--leader_proxy_read_timeout` (Optional. Default: 10000):
    Maximum time, in milliseconds, for reading from the
    current Marathon leader.

Furthermore, leader proxying now uses HTTPS to talk to the leader if `--http_disable` was specified.

These bugs are now obsolete:

- #1540 A marathon instance should never proxy to itself.
- #1541 Proxying Marathon requests should use timeouts.
- #1556 Proxying doesn't work for HTTPS.

#### Relative URL paths in the UI

The UI now uses relative URL paths making it easier to run Marathon behind a reverse proxy.

#### Restrict the number of versions by default

In Marathon, most state is versioned. This includes app definitions and group definitions. Marathon already allowed
restricting the number of versions that are kept by `--zk_max_versions` but you had to specify that explicitly.

Since some of our users were running into problems with too many versions, we decided to restrict to
a maximum number of `25` versions by default. We recommend to set this to an even lower number, e.g. `3`, since
higher numbers impact performance negatively.

#### New format for the `http_endpoints` command line parameter

We changed the format of the `http_endpoints` command line parameter from a
space-separated to a comma-separated list of endpoints, in order to be
more consistent with the documentation and with the format used in other
parameters.

WARNING: If you use the `http_endpoints` parameter with multiple space
separated URLs, you will need to migrate to the comma-separated format.

#### Do not delay task launches anymore as a result of failed health checks
Marathon uses an exponential back off strategy to delay further task launches after task failures. This should
prevent keeping the cluster busy with task launches which are set up to fail anyway. The delay was also increased
when health checks failed leading to delayed recovery.

Since health checks typically (depending on configuration) take a while to determine that a task is unhealthy,
this already delays restarting sufficiently.

#### Removed deprecated command line arguments `zk_hosts` and `zk_state`

The command line arguments `zk_hosts` and `zk_state` were deprecated for some time and got removed in this version.
Use the `--zk` command line argument to define the ZooKeeper connection string.


#### servicerouter.py

Is a replacement for the haproxy-marathon-bridge. It reads Marathon task information and generates haproxy
configuration. It supports advanced functions like sticky sessions, HTTP to HTTPS redirection, SSL offloading, VHost
support and templating.

#### Be more careful about using `ulimit` in startup script

The startup script now only increases the maximum number of open files if the limit is too low and if the
script is started as `root`.


### Fixed Bugs

- #1259 - Reject null bodies in REST API
- #1540 - A marathon instance should never proxy to itself
- #1541 - Proxying Marathon requests should use timeouts
- #1556 - Proxying doesn't work for HTTPS
- #1365 - servicePorts are not copied into ports
- #1389 - Don't set ulimit in marathon-framework
- #1452 - Remove ulimit changes from shell script
- #1446 - Validation for app creation & update should not differ
- #1456 - Marathon delaying app start by 70min after cluster reboot
- #1481 - App stays in locked state
- #1520 - Marathon don't match correctly the resources for a defined role
- #1522 - Disk resource quota not communicated to Mesos on task launch
- #1583 - Task uses invalid resources: disk(*):0
- #1564 - args[] does not work
- #1569 - `http_endpoints` not being split on comma
- #1572 - Remove `$$EnhancerByGuice$$...` from class names in metrics


------------------------------------------------------------


## Changes from 0.8.1 to 0.8.2

#### New health check option `ignoreHttp1xx`

When set to true, the health check for the given app will ignore HTTP
response codes 100 to 199, in contrast to considering it as unhealthy. With this unbounded task startup times can be handled: the tasks are neither
healthy nor unhealthy as long as e.g. "100 - continue" is returned.

#### HTTPS support for health checks

Health checks now work with HTTPS.

#### Faster (configurable) task distribution

Mesos frequently sends resource offers to Marathon (and all other frameworks). Each offer will represent the available resources of a single node in the cluster. Before this change, Marathon would only start a single task per resource offer, which led to slow task launching in smaller clusters. In order to speed up task launching and use the resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm which tries to start as many tasks as possible per task offer cycle. The maximum number of tasks to start is configurable with the following startup parameters:

`--max_tasks_per_offer` (default 1): The maximum number of tasks to start on a single offer per cycle

`--max_tasks_per_offer_cycle` (default 1000): The maximum number of tasks to start in total per cycle

**Example**

Given a cluster with 200 nodes and the default settings for task launching. If we want to start 2000 tasks, it would take at least 10 cycles, because we are only starting 1 task per offer, leading to a total maximum of 200. If we change the `max_tasks_per_offer` setting to 10, we could start 1000 tasks per offer (the default setting for `max_tasks_per_offer_cycle`), reducing the necessary cycles to 2. If we also adjust the `max_tasks_per_offer_cycle ` to 2000, we could start all tasks in a single cycle (given we receive offers for all nodes).

**Important**

Starting too many tasks at once can lead to a higher number of status updates being sent to Marathon than it can currently handle. We will improve the number of events Marathon can handle in a future version. A maximum of 1000 tasks has proven to be a good default for now. `max_tasks_per_offer` should be adjusted so that `NUM_MESOS_SLAVES * max_tasks_per_offer == max_tasks_per_offer_cycle `. E.g. in a cluster of 200 nodes it should be set to 5.

#### Security settings configurable through env variables

Security settings are now configurable through the following environment variables:

`$MESOSPHERE_HTTP_CREDENTIALS` for HTTP authentication (e.g. `export MESOSPHERE_HTTP_CREDENTIALS=user:password`)

`$MESOSPHERE_KEYSTORE_PATH` + `$MESOSPHERE_KEYSTORE_PASS` for SSL settings

#### Isolated deployment rollbacks

Marathon allows rolling back running deployments via the [DELETE /v2/deployments/{deploymentId}](https://mesosphere.github.io/marathon/docs/rest-api.html#delete-/v2/deployments/%7Bdeploymentid%7D) command or the "rollback" button in the GUI.

In prior Marathon versions, deployment rollbacks reverted all applications to the state before the selected deployment. If you performed concurrent deployments, these would also be reverted.

Now Marathon isolates the changes of the selected deployment and calculates a deployment plan which prevents changing unrelated apps.

#### Empty groups can be overwritten by apps

Instead of declining the creation of an app with the same name as a previously existing group, the group will now be removed if empty and replaced with the app.

#### Performance improvements

App and task related API calls should be considerably faster with large amounts of tasks now.


------------------------------------------------------------


## Changes from 0.8.0 to 0.8.1

#### New option `dryRun` on endpoint `PUT /v2/groups/{id}`

When sending a group definition to this endpoint with `dryRun=true`,
it will return the deployment steps Marathon would execute to deploy
this group.

#### New endpoint POST `/v2/tasks/delete`

Takes a JSON object containing an array of task ids and kills them.
If `?scale=true` the tasks will not be restarted and the `instances`
field of the affected apps will be adjusted.

#### POST `/v2/apps` rejects existing ids

If an app with an already existing id is posted to this endpoint,
it will now be rejected

#### PUT `/v2/apps/{id}` always returns deployment info

In versions <= 0.8.0 it used to return the complete app definition
if the resource didn't exist before. To be consistent in the response,
it has been changed to always return the deployment info instead. However
it still return a `201 - Created` if the resource didn't exist.

#### GET `/v2/queue` includes delay

In 0.8.0 the queueing behavior has changed and the output of this endpoint
did not contain the delay field anymore. In 0.8.1 we re-added this field.

