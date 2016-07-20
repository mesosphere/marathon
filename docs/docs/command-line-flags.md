---
title: Command Line Flags
---

# General Environment Variables

* `JAVA_OPTS`  Default: `-Xmx512m`
    Any options that should be passed to the JVM that marathon will run in.


# Marathon Command Line Flags

## Core Functionality

These flags control the core functionality of the Marathon server.


### Note - Command Line Flags May Be Specified Using Environment Variables

The core functionality flags can be also set by environment variable `MARATHON_OPTION_NAME` (the option name with a `MARATHON_` prefix added to it), for example `MARATHON_MASTER` for `--master` option.  Please note that command line options precede environment variables.  This means that if the `MARATHON_MASTER` environment variable is set and `--master` is supplied on the command line, then the environment variable is ignored.

### Required Flags

* `--master` (Required): The URL of the Mesos master. The format is a
    comma-delimited list of of hosts like `zk://host1:port,host2:port/mesos`.
    If using ZooKeeper, pay particular attention to the leading `zk://` and
    trailing `/mesos`! If not using ZooKeeper, standard URLs like
    `http://localhost` are also acceptable.

### Optional Flags

* `--artifact_store` (Optional. Default: None): URL to the artifact store.
    Examples: `"hdfs://localhost:54310/path/to/store"`,
    `"file:///var/log/store"`. For details, see the
    [artifact store]({{ site.baseurl }}/docs/artifact-store.html) docs.
* `--access_control_allow_origin` (Optional. Default: None):
    Comma separated list of allowed originating domains for HTTP requests.
    The origin(s) to allow in Marathon. Not set by default.
    Examples: `"*"`, or `"http://localhost:8888, http://domain.com"`.
* <span class="label label-default">v0.13.0</span> `--[disable_]checkpoint` (Optional. Default: enabled):
    Enable checkpointing of tasks.
    Requires checkpointing enabled on slaves. Allows tasks to continue running
    during mesos-slave restarts and Marathon scheduler failover.  See the
    description of `--failover_timeout`.
* <span class="label label-default">v1.0.0</span> `--enable_features` (Optional. Default: None):
    Enable the selected features. Options to use:
    - "vips" can be used to enable the networking VIP integration UI.
    - "task\_killing" can be used to enable the TASK\_KILLING state in Mesos (0.28 or later)
    - "external\_volumes" can be used if the cluster is configured to use external volumes.
    Example: `--enable_features vips,task_killing,external_volumes`
* `--executor` (Optional. Default: "//cmd"): Executor to use when none is
    specified.
* `--failover_timeout` (Optional. Default: 604800 seconds (1 week)): The
    failover_timeout for Mesos in seconds.  If a new Marathon instance has
    not re-registered with Mesos this long after a failover, Mesos will shut
    down all running tasks started by Marathon.  Requires checkpointing to be
    enabled.
* `--framework_name` (Optional. Default: marathon): The framework name
    to register with Mesos.
* <span class="label label-default">v0.13.0</span> `--[disable_]ha` (Optional. Default: enabled):
    Run Marathon in HA mode with leader election.
    Allows starting an arbitrary number of other Marathons but all need to be
    started in HA mode. This mode requires a running ZooKeeper.
* `--hostname` (Optional. Default: hostname of machine): The advertised hostname
    that is used for the communication with the mesos master.
    The value is also stored in the persistent store so another standby host can redirect to the elected leader.
    _Note: Default is determined by
    [`InetAddress.getLocalHost`](http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getLocalHost())._
* `--webui_url` (Optional. Default: None): The url of the Marathon web ui. It
    is passed to Mesos to be used in links back to the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html). If not set,
    the url to the leading instance will be sent to Mesos.
* <span class="label label-default">v0.9.0</span> `--leader_proxy_connection_timeout` (Optional. Default: 5000):
    Maximum time, in milliseconds, for connecting to the
    current Marathon leader from this Marathon instance.
* <span class="label label-default">v0.9.0</span> `--leader_proxy_read_timeout` (Optional. Default: 10000):
    Maximum time, in milliseconds, for reading from the
    current Marathon leader.
* `--local_port_max` (Optional. Default: 20000): Max port number to use when dynamically assigning globally unique
    service ports to apps. If you assign your service port statically in your app definition, it does
    not have to be in this range.
* `--local_port_min` (Optional. Default: 10000): Min port number to use when dynamically assigning globally unique
    service ports to apps. If you assign your service port statically in your app definition, it does
    not have to be in this range.
* `--mesos_role` (Optional. Default: None): Mesos role for this framework. If set, Marathon receives resource offers
    for the specified role in addition to resources with the role designation '*'.
* <span class="label label-default">v0.9.0</span> `--default_accepted_resource_roles` (Optional. Default: all roles):
    Default for the `"acceptedResourceRoles"`
    attribute as a comma-separated list of strings. All app definitions which do not specify this attribute explicitly
    use this value for launching new tasks. Examples: `*`, `production,*`, `production`
* `--mesos_user` (Optional. Default: current user): Mesos user for
    this framework. _Note: Default is determined by
    [`SystemProperties.get("user.name")`](http://www.scala-lang.org/api/current/index.html#scala.sys.SystemProperties@get\(key:String\):Option[String])._
* `--reconciliation_initial_delay` (Optional. Default: 15000 (15 seconds)): The
    delay, in milliseconds, before Marathon begins to periodically perform task
    reconciliation operations.
* `--reconciliation_interval` (Optional. Default: 600000 (10 minutes)): The
    period, in milliseconds, between task reconciliation operations.
* `--scale_apps_initial_delay` (Optional. Default: 15000 (15 seconds)): The
    delay, in milliseconds, before Marathon begins to periodically perform
    application scaling operations.
* `--scale_apps_interval` (Optional. Default: 300000 (5 minutes)): The period,
    in milliseconds, between application scaling operations.
* `--task_launch_timeout` (Optional. Default: 300000 (5 minutes)):
    Time, in milliseconds, to wait for a task to enter the `TASK_RUNNING` state
    before killing it.
* `--event_subscriber` (Optional. Default: None): Event subscriber module to
    enable. Currently the only valid value is `http_callback`.
* `--http_endpoints` (Optional. Default: None): Pre-configured http callback
    URLs. Valid only in conjunction with `--event_subscriber http_callback`.
    Additional callback URLs may also be set dynamically via the REST API.
* `--zk` (Optional. Default: `zk://localhost:2181/marathon`): ZooKeeper URL for storing state.
    Format: `zk://host1:port1,host2:port2,.../path`
    - <span class="label label-default">v1.1.2</span> Format: `zk://user@pass:host1:port1,user@pass:host2:port2,.../path`.
    When authentication is enabled the default ACL will be changed and all subsequent reads must be done using the same auth.
* `--zk_max_versions` (Optional. Default: None): Limit the number of versions
    stored for one entity.
* `--zk_timeout` (Optional. Default: 10000 (10 seconds)): Timeout for ZooKeeper
    in milliseconds.
*  <span class="label label-default">v0.9.0</span> `--zk_session_timeout` (Optional. Default: 1.800.000 (30 minutes)): Timeout for ZooKeeper
    sessions in milliseconds.
* <span class="label label-default">v1.1.2</span> `--zk_max_node_size` (Optional. Default: 1 MiB):
    Maximum allowed ZooKeeper node size (in bytes).
* <span class="label label-default">v1.2.0</span> `--[disable_]mesos_authentication`  (Optional. Default: disabled):
    If enabled, framework authentication will be used while registering with Mesos with principal and optional secret.
* `--mesos_authentication_principal` (Optional.): The Mesos principal used for
    authentication and for resource reservations
* <span class="label label-default">v1.2.0</span> `--mesos_authentication_secret` (Optional.): The secret to use for authentication.
    Please also consider using `--mesos_authentication_secret_file` to specify the secret in a file.
    Only specify `--mesos_authentication_secret_file` or `--mesos_authentication_secret`
* `--mesos_authentication_secret_file` (Optional.): The path to the Mesos secret
    file containing the authentication secret
* `--mesos_leader_ui_url` (Optional.): The URL to the Mesos master facade. By default this
    value is detected automatically when framework is registered or new Mesos leader is
    elected.
    Format: `protocol://host:port/`
    _Note: When this option is set given url should always load balance to current Mesos master
* <span class="label label-default">Deprecated</span>`--marathon_store_timeout` (Optional.): Maximum time
    in milliseconds, to wait for persistent storage operations to complete.
* <span class="label label-default">v0.10.0</span> `--env_vars_prefix` (Optional. Default: None):
    The prefix to add to the name of task's environment variables created
    automatically by Marathon.
    _Note: This prefix will not be added to variables that are already prefixed,
    such as `MESOS_TASK_ID` and `MARATHON_APP_ID`
* <span class="label label-default">v0.11.1</span> `--[disable_]zk_compression`  (Optional. Default: enabled):
    Enable compression of zk nodes, if the size of the node is bigger than the configured threshold.
* <span class="label label-default">v0.11.1</span> `--zk_compression_threshold` (Optional. Default:
   64 KB): Threshold in bytes, when compression is applied to the zk node
* <span class="label label-default">v0.11.1</span> `--max_apps` (Optional. Default: None):
    The maximum number of applications that may be created.
* <span class="label label-default">v0.13.0</span> `--store_cache` (Optional. Default: true): Enable an in memory cache for the storage layer.
* <span class="label label-default">v0.13.0</span> `--on_elected_prepare_timeout` (Optional. Default: 3 minutes):
    The timeout for preparing the Marathon instance when elected as leader.
* <span class="label label-default">v0.14.1</span> `--http_event_callback_slow_consumer_timeout` (Optional. Default: 10 seconds):
    A http event callback consumer is considered slow, if the delivery takes longer than this timeout.
* `--default_network_name` (Optional.): Network name, injected into applications' `ipAddress{}` specs that do not define their own `networkName`.
* <span class="label label-default">v0.15.4</span> `--task_lost_expunge_gc` (Optional. Default: 24 hours):
    This is the length of time in milliseconds, until a lost task is garbage collected and expunged from the task tracker and task repository.
* <span class="label label-default">v0.15.4</span> `--task_lost_expunge_initial_delay` (Optional. Default: 5 minutes):
    This is the length of time, in milliseconds, before Marathon begins to periodically perform task expunge gc operations
* <span class="label label-default">v0.15.4</span> `--task_lost_expunge_interval` (Optional. Default: 1 hour):
    This is the length of time in milliseconds, for lost task gc operations.
* `--mesos_heartbeat_interval` (Optional. Default: 15 seconds):
    (milliseconds) in the absence of receiving a message from the mesos master during a time window of this duration,
    attempt to coerce mesos into communicating with marathon.
* `--mesos_heartbeat_failure_threshold` (Optional. Default: 5):
    after missing this number of expected communications from the mesos master, infer that marathon has become
    disconnected from the master.

## Tuning Flags for Offer Matching/Launching Tasks

Mesos frequently sends resource offers to Marathon (and all other frameworks). Each offer will represent the
available resources of a single node in the cluster. Before this <span class="label label-default">v0.8.2</span>,
Marathon would only start a single task per
resource offer, which led to slow task launching in smaller clusters.


### Marathon after 0.11.0 (including)

In order to speed up task launching and use the
resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm which tries
to start as many tasks as possible per task offer cycle. The maximum number of tasks to start on one offer is
configurable with the following startup parameters:

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer` (Optional. Default: 5): Launch at most this
    number of tasks per Mesos offer. Usually,
    there is one offer per cycle and slave. You can speed up launching tasks by increasing this number.

To prevent overloading Mesos itself, you can also restrict how many tasks Marathon launches per time interval.
By default, we allow 100 unconfirmed task launches every 30 seconds. In addition, Marathon launches
more tasks when it gets feedback about running and healthy tasks from Mesos.

* <span class="label label-default">v0.11.0</span> `--launch_token_refresh_interval` (Optional. Default: 30000):
    The interval (ms) in which to refresh the launch tokens to `--launch_token_count`.
* <span class="label label-default">v0.11.0</span> `--launch_tokens` (Optional. Default: 100):
    Launch tokens per interval.

To prevent overloading Marathon and maintain speedy offer processing, there is a timeout for matching each
incoming resource offer, i.e. finding suitable tasks to launch for incoming offers.

* <span class="label label-default">v0.11.0</span> `--offer_matching_timeout` (Optional. Default: 1000):
    Offer matching timeout (ms). Stop trying to match additional tasks for this offer after this time.
    All already matched tasks are launched.

All launched tasks are stored before launching them. There is also a timeout for this:

* <span class="label label-default">v0.11.0</span> `--save_tasks_to_launch_timeout` (Optional. Default: 3000):
    Timeout (ms) after matching an offer for saving all matched tasks that we are about to launch.
    When reaching the timeout, only the tasks that we could save within the timeout are also launched.
    All other task launches are temporarily rejected and retried later.

If the Mesos master fails over or in other unusual circumstances, a launch task request might get lost.
You can configure how long Marathon waits for the first `TASK_STAGING` update.

* <span class="label label-default">v0.11.0</span> `--task_launch_confirm_timeout` (Optional. Default: 300000 (5 minutes)):
  Time, in milliseconds, to wait for a task to enter the `TASK_STAGING` state before killing it.

When the task launch requests in Marathon change because an app definition changes or a backoff delay is overdue,
Marathon can request all available offers from Mesos again -- even those that it has recently rejected. To avoid
calling the underlying `reviveOffers` API call to often, you can configure the minimal delay between subsequent
invocations of this call.

* <span class="label label-default">v0.11.0</span> `--min_revive_offers_interval` (Optional. Default: 5000):
    Do not ask for all offers (also already seen ones) more often than this interval (ms).

The order in which mesos receives `reviveOffers` and `declineOffer` calls is not guaranteed. Therefore, as
long as we still need offers to launch tasks, we repeat the `reviveOffers` call for `--revive_offers_repetitions`
times so that our last `reviveOffers` will be received after all relevant `declineOffer` calls with high
probability.

* <span class="label label-default">v0.11.0</span> `--revive_offers_repetitions` (Optional. Default: 3):
    Repeat every reviveOffer request this many times, delayed by the `--min_revive_offers_interval`.

If you want to disable calling reviveOffers (not recommended), you can use:

* <span class="label label-default">v0.11.0</span> `--disable_revive_offers_for_new_apps`

When Marathon has no current use for an offer, it will decline the offer for a configurable period. This period is
configurable. A short duration might lead to resource starvation for other frameworks if you run many frameworks
in your cluster. You should only need to reduce it if you use `--disable_revive_offers_for_new_apps`.

* `--decline_offer_duration` (Default: 120 seconds) The duration (milliseconds) for which to decline offers by default.


### Marathon after 0.8.2 (including) and before 0.11.0

In order to speed up task launching and use the
resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm which tries
to start as many tasks as possible per task offer cycle. The maximum number of tasks to start is configurable with
the following startup parameters:

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer` (Optional. Default: 5): Launch at most this
    number of tasks per Mesos offer. Usually,
    there is one offer per cycle and slave. You can speed up launching tasks by increasing this number.

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer_cycle` (Optional. Default: 1000): Launch at
    most this number of tasks per Mesos offer cycle.
    A larger value speeds up launching new tasks.
    Yet, choosing a too large value might overwhelm Mesos/Marathon with processing task updates.

**Example**

Given a cluster with 200 nodes and the default settings for task launching. If we want to start 2000 tasks, it would
take at least 10 cycles, because we are only starting 1 task per offer (and hence 200 per cycle), leading to a total maximum of 200. If we
change the `max_tasks_per_offer` setting to 10, we could start 1000 tasks per offer cycle (the default setting for
`max_tasks_per_offer_cycle`), reducing the necessary cycles to 2. If we also adjust the `max_tasks_per_offer_cycle `
to 2000, we could start all tasks in a single cycle (given we receive offers for all nodes).

**Important**

Starting too many tasks at once can lead to a higher number of status updates being sent to Marathon than it can
currently handle. We will improve the number of events Marathon can handle in a future version. A maximum of 1000
tasks has proven to be a good default for now. `max_tasks_per_offer` should be adjusted so that `NUM_MESOS_SLAVES *
max_tasks_per_offer == max_tasks_per_offer_cycle `. E.g. in a cluster of 200 nodes it should be set to 5.

## Web Site Flags

The Web Site flags control the behavior of Marathon's web site, including the user-facing site and the REST API. They are inherited from the
[Chaos](https://github.com/mesosphere/chaos) library upon which Marathon and its companion project [Chronos](https://github.com/mesos/chronos) are based.

### Optional Flags

* `--assets_path` (Optional. Default: None): Local file system path from which
    to load assets for the web UI. If not supplied, assets are loaded from the
    packaged JAR.
* `--http_address` (Optional. Default: all): The address on which to listen
    for HTTP requests.
* `--http_credentials` (Optional. Default: None): Credentials for accessing the
    HTTP service in the format of `username:password`. The username may not
    contain a colon (:). May also be specified with the `MESOSPHERE_HTTP_CREDENTIALS` environment variable.
* `--http_port` (Optional. Default: 8080): The port on which to listen for HTTP
    requests.
* `--disable_http` (Optional.): Disable HTTP completely. This is only allowed if you configure HTTPS.
    HTTPS stays enabled. <span class="label label-default">v0.9.0</span> Also enables forwarding queries to the
    leader via HTTPS instead of HTTP.
* `--https_address` (Optional. Default: all): The address on which to listen
    for HTTPS requests.
* `--https_port` (Optional. Default: 8443): The port on which to listen for
    HTTPS requests. Only used if `--ssl_keystore_path` and `--ssl_keystore_password` are set.
* `--http_realm` (Optional. Default: Mesosphere): The security realm (aka 'area') associated with the credentials
* `--ssl_keystore_path` (Optional. Default: None): Path to the SSL keystore. HTTPS (SSL)
    will be enabled if this option is supplied. Requires `--ssl_keystore_password`.
    May also be specified with the `MESOSPHERE_KEYSTORE_PATH` environment variable.
* `--ssl_keystore_password` (Optional. Default: None): Password for the keystore
    supplied with the `ssl_keystore_path` option. Required if `ssl_keystore_path` is supplied.
    May also be specified with the `MESOSPHERE_KEYSTORE_PASS` environment variable.
* `--leader_proxy_ssl_ignore_hostname` (Optional. Default: false): Do not
    verify that the hostname of the Marathon leader matches the one in the SSL
    certificate when proxying API requests to the current leader.
*  <span class="label label-default">v0.10.0</span> `--http_max_concurrent_requests` (Optional.): the maximum number of
    concurrent HTTP requests, that is allowed concurrently before requests get answered directly with a
    HTTP 503 Service Temporarily Unavailable.

### Metrics Flags

* <span class="label label-default">v0.13.0</span> `--[disable_]metrics` (Optional. Default: enabled):
    Expose the execution time per method via the metrics endpoint (/metrics) using code instrumentation.
    Enabling this might noticeably degrade performance but it helps finding performance problems.
    These measurements can be disabled with --disable_metrics. Other metrics are not affected.
* <span class="label label-default">v0.13.0</span> `--reporter_graphite` (Optional. Default: disabled):
    Report metrics to [Graphite](http://graphite.wikidot.com) as defined by the given URL.
    Example: `tcp://localhost:2003?prefix=marathon-test&interval=10`
    The URL can have several parameters to refine the functionality.
    * prefix: (Default: None) the prefix for all metrics
    * interval: (Default: 10) the interval to report to graphite in seconds
* <span class="label label-default">v0.13.0</span> `--reporter_datadog` (Optional. Default: disabled):
    Report metrics to [Datadog](https://www.datadoghq.com) as defined by the given URL.
    Either use UDP to talk to a datadog agent or HTTP to talk directly to DatadogHQ.
    Example (UDP to agent): `udp://localhost:8125?prefix=marathon-test&tags=marathon&interval=10`
    Example (HTTP to DataDogHQ): `http://datadog?apiKey=abc&prefix=marathon-test&tags=marathon&interval=10`
    The URL can have several parameters to refine the functionality.
    * expansions: (Default: all) which metric data should be expanded. can be a list of: count,meanRate,1MinuteRate,5MinuteRate,15MinuteRate,min,mean,max,stddev,median,p75,p95,p98,p99,p999
    * interval: (Default: 10) the interval in seconds to report to Datadog
    * prefix: (Default: marathon_test) the prefix is prepended to all metric names
    * tags: (Default: empty) the tags to send with each metric. Can be either simple value like `foo` or key value like `foo:bla`
    * apiKey: (Default: empty) the api key to use, when directly connecting to Datadog (HTTP)

### Debug Flags

* <span class="label label-default">v0.8.2</span> `--logging_level` (Optional):
    Set the logging level of the application.
    Use one of `off`, `fatal`, `error`, `warn`, `info`, `debug`, `trace`, `all`.
* <span class="label label-default">v0.13.0</span> `--[disable_]tracing` (Optional. Default: disabled):
    Enable tracing for all service method calls.
    Log a trace message around the execution of every service method.
