---
title: Command-Line Flags
---

# General Environment Variables

* `JAVA_OPTS`  Default: `-Xmx512m`
    Any options that should be passed to the JVM that marathon will run in.

# Marathon Command-Line Flags

## Core Functionality

These flags control the core functionality of the Marathon server.


### Specifying Command-Line Flags with Environment Variables

The core functionality flags can be also set by environment variable `MARATHON_` + the option name in all caps. For example, `MARATHON_MASTER` for the `--master` option.

For boolean values, set the environment variable with empty value. For example, use `MARATHON_HA=` to enable `--ha` or `MARATHON_DISABLE_HA=` for `--disable_ha`.

You may not both specify the same command-line parameter as an environment variable and an actual command-line parameter.

### Native Package Customization

When using Debian packages, the ideal way to customize Marathon is to specify command-line flags via environment variables, in `/etc/default/marathon`.

### Required Flags

* `--master` (Required): The URL of the Mesos master. The format is a
    comma-delimited list of of hosts like `zk://host1:port,host2:port/mesos`.
    If using ZooKeeper, pay particular attention to the leading `zk://` and
    trailing `/mesos`! If not using ZooKeeper, standard URLs like
    `http://localhost` are also acceptable.

### Optional Flags

* `--access_control_allow_origin` (Optional. Default: None):
    Comma separated list of allowed originating domains for HTTP requests.
    The origin(s) to allow in Marathon. Not set by default.
    Examples: `"*"`, or `"http://localhost:8888, http://domain.com"`.
* <span class="label label-default">v0.13.0</span> `--[disable_]checkpoint` (Optional. Default: enabled):
    Enable checkpointing of tasks.
    Requires checkpointing enabled on agents. Allows tasks to continue running
    during mesos-agent restarts and Marathon scheduler failover.  See the
    description of `--failover_timeout`.
* <span class="label label-default">v1.0.0</span> `--enable_features` (Optional. Default: None):
    Enable the selected features. Options to use:
    - "vips" can be used to enable the networking VIP integration UI.
    - "task\_killing" can be used to enable the TASK\_KILLING state in Mesos (0.28 or later)
    - "external\_volumes" can be used if the cluster is configured to use external volumes.
    - "maintenance_mode" can be used to respect maintenance window during offer matching.
    Example: `--enable_features vips,task_killing,external_volumes`
* <span class="label label-default">v1.6.488</span> `--deprecated_features` (Optional. Default: None):
    Comma-delimited list indicating which Marathon deprecated features should continue to be enabled. Read more about
    [Deprecation](deprecation.html).
    Example: `--deprecated_features feature_one,feature_two,feature_three`
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
    for the specified role in addition to resources with the role designation '*'. Marathon currently 
    supports only one Mesos role. Support for multiple roles will be added in the future. _Note: When using Mesos prior to version 1.3, this parameter is applied when the framework registers with Mesos for the first time, and changing it after that has no effect if the framework is re-registered._
* <span class="label label-default">v0.9.0</span> `--default_accepted_resource_roles` (Optional. Default: all roles):
    Default for the `"acceptedResourceRoles"`
    attribute as a comma-separated list of strings. All app definitions which do not specify this attribute explicitly
    use this value for launching new tasks. Examples: `*`, `production,*`, `production`
* `--mesos_user` (Optional. Default: current user): Mesos user for
    this framework. _Note: Default is determined by
    [`SystemProperties.get("user.name")`](http://www.scala-lang.org/api/current/index.html#scala.sys.SystemProperties@get\(key:String\):Option[String]),
    which defaults to the system user as which Marathon is running. The value of this field is only used during initial
    registration, changing it later has no affect.
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
    before killing it. See also `--task_launch_confirm_timeout`.
* `--zk` (Optional. Default: `zk://localhost:2181/marathon`): ZooKeeper URL for storing state.
    Format: `zk://host1:port1,host2:port2,.../path`
    - <span class="label label-default">v1.1.2</span> Format: `zk://user@pass:host1:port1,user@pass:host2:port2,.../path`.
    When authentication is enabled the default ACL will be changed and all subsequent reads must be done using the same auth.
* `--zk_max_versions` (Optional. Default: 50): Limit the number of versions
    stored for one entity.
* `--zk_timeout` (Optional. Default: 10000 (10 seconds)):
    Timeout for ZooKeeper operations in milliseconds.
    If this timeout is exceeded, the ZooKeeper operation is marked as failed.
    This timeout is also used for all REST endpoint operations: if an operation takes longer than this timeout, the request will be answered with a failure.
*  <span class="label label-default">v1.5.0</span> `--zk_connection_timeout` (Optional. Default: 10000 (10 seconds)):
    Timeout to connect to ZooKeeper in milliseconds.
    If Marathon is unable to connect to the ZK cluster during this timeout, Marathon will give up after a few retries.
    In Marathon versions prior to v1.5.0 `--zk_timeout` is used instead.
*  <span class="label label-default">v0.9.0</span> `--zk_session_timeout` (Optional. Default: 10000 (10 seconds)):
    Timeout for ZooKeeper sessions in milliseconds.
    If Marathon becomes partitioned from the ZK cluster and can not reconnect during this timeout then the session will expire and the connection will be closed.
    If this happens to the leader then the leader will abdicate.
    The default value from Marathon version 0.9 to 0.13 (including) was 30 minutes instead of 10 seconds.
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
* `--default_network_name` (Optional.): Network name, injected into applications' `ipAddress{}` specs that do not define their own `networkName`.
* <span class="label label-default">v0.15.4 Deprecated since v1.4.0</span>`--task_lost_expunge_gc` (Optional. Default: 75 seconds):
    This is the length of time in milliseconds, until a lost task is garbage collected and expunged from the task tracker and task repository.
    Since v1.4.0 an UnreachableStrategy can be defined per application or pod definition.
* <span class="label label-default">v0.15.4</span> `--task_lost_expunge_initial_delay` (Optional. Default: 5 minutes):
    This is the length of time, in milliseconds, before Marathon begins to periodically perform task expunge gc operations
* <span class="label label-default">v0.15.4</span> `--task_lost_expunge_interval` (Optional. Default: 30 seconds):
    This is the length of time in milliseconds, for lost task gc operations.
* `--mesos_heartbeat_interval` (Optional. Default: 15 seconds):
    (milliseconds) in the absence of receiving a message from the mesos master during a time window of this duration,
    attempt to coerce mesos into communicating with marathon.
* `--mesos_heartbeat_failure_threshold` (Optional. Default: 5):
    after missing this number of expected communications from the mesos master, infer that marathon has become
    disconnected from the master.
* `--mesos_bridge_name` (Optional. Default: mesos-bridge):
    The name of the Mesos CNI network used by MESOS-type containers configured to use bridged networking
* <span class="label label-default">v1.5.0</span>`--backup_location` (Optional. Default: None):
    Create a backup before a migration is applied to the persistent store.
    This backup can be used to restore the state at that time.
    Currently two providers are allowed:
    - File provider: file:///path/to/file
    - S3 provider (experimental): s3://bucket-name/key-in-bucket?access_key=xxx&secret_key=xxx&region=eu-central-1
      Please note: access_key and secret_key are optional.
      If not provided, the [AWS default credentials provider chain](http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) is used to look up aws credentials.
* <span class="label label-default">v1.6.0</span>`--draining_seconds` (Optional. Default: 0):
    Time (in seconds) when Marathon will start declining offers before a [maintenance window](http://mesos.apache.org/documentation/latest/maintenance/) start time.
    **Note:** This flag has no effect if `--disable_maintenance_mode` is specified.
* <span class="label label-default">> v1.6.352</span>`--max_running_deployments` (Optional. Default: 100):
    Maximum number of concurrently running deployments. Should the user try to submit more updates than set by this flag a HTTP 403 Error is returned with an explanatory error message.
* `--[disable_]suppress_offers` (Optional. Default: disabled)
    Controls whether or not Marathon will suppress offers if there is nothing to launch. Enabling helps the performance
    of Mesos in larger clusters, but enabling this flag will cause Marathon to more slowly release reservations.
* <span class="label label-default">v1.6.0</span>`--[disable]_maintenance_mode` (Optional. Default: enabled) Specifies
    if Marathon should enable maintenance mode support. See the [maintenance mode docs](./maintenance-mode.html) for
    more information.

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

* <span class="label label-default">v1.4.0</span> `--max_instances_per_offer` (Optional. Default: 5): Launch at most this
    number of instances per Mesos offer. Usually,
    there is one offer per cycle and agent. You can speed up launching tasks by increasing this number.
    
To prevent overloading Mesos itself, you can also restrict how many tasks Marathon launches per time interval.
By default, we allow 100 unconfirmed task launches every 30 seconds. In addition, Marathon launches
more tasks when it gets feedback about running and healthy tasks from Mesos.

* <span class="label label-default">v0.11.0</span> `--launch_token_refresh_interval` (Optional. Default: 30000):
    The interval (ms) in which to refresh the launch tokens to `--launch_token_count`.
* <span class="label label-default">v0.11.0</span> `--launch_tokens` (Optional. Default: 100):
    Launch tokens per interval.

To prevent overloading Marathon and maintain speedy offer processing, there is a timeout for matching each
incoming resource offer, i.e. finding suitable tasks to launch for incoming offers.

* <span class="label label-default">v0.11.0</span> `--offer_matching_timeout` (Optional. Default: 3000):
  <span class="label label-default">v1.5.0</span> The default value was 1000 in Version 0.11.0 until 1.5.0
    Offer matching timeout (ms). Stop trying to match additional tasks for this offer after this time.
    All already matched tasks are launched.
    Note: A small timeout could lead to ineffective offer matching. 
          A big timeout can lead to offer starvation in a cluster with a lot of frameworks. 

All launched tasks are stored before launching them. There is also a timeout for this:

* <span class="label label-default">v0.11.0</span> `--task_launch_confirm_timeout` (Optional. Default: 300000 (5 minutes)):
  Time, in milliseconds, to wait for a task to enter the `TASK_STAGING` state before killing it. Also see `--task_launch_timeout`.

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

* <span class="label label-default">v1.6.x</span> `--gpu_scheduling_behavior` (Default: undefined) Defines how offered GPU resources should be treated. Possible settings are `undefined`, `restricted` and `unrestricted`. Read more about [Preferential GPU scheduling](preferential-gpu-scheduling.html).


### Marathon after 0.8.2 (including) and before 0.11.0

In order to speed up task launching and use the
resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm which tries
to start as many tasks as possible per task offer cycle. The maximum number of tasks to start is configurable with
the following startup parameters:

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer` (Optional. Default: 5): Launch at most this
    number of tasks per Mesos offer. Usually,
    there is one offer per cycle and agent. You can speed up launching tasks by increasing this number.

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
tasks has proven to be a good default for now. `max_tasks_per_offer` should be adjusted so that `NUM_MESOS_AGENTS *
max_tasks_per_offer == max_tasks_per_offer_cycle `. E.g. in a cluster of 200 nodes it should be set to 5.

## Web Site Flags

The Web Site flags control the behavior of Marathon's web site, including the user-facing site and the REST API.

### Optional Flags

*  <span class="label label-default">Deprecated</span> `--assets_path` (Optional. Default: None): Local file system path from which
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
* <span class="label label-default">v1.7.0</span> `--leader_proxy_max_open_connections` (Optional. Default: 64):
    Specifies the number of maximum, concurrent open HTTP connections allowed when proxying from the standby to the
    current leader. Does not apply when using the deprecated sync proxy.
* `--[disable_]http_compression` (Optional. Default: enabled): Specifies whether Marathon should compress HTTP responses
    for clients that support it. Disabling will reduce the CPU burden on Marathon to service API requests.
*  <span class="label label-default">v0.10.0</span> `--http_max_concurrent_requests` (Optional.): the maximum number of
    concurrent HTTP requests, that is allowed concurrently before requests get answered directly with a
    HTTP 503 Service Temporarily Unavailable.

### Metrics Flags

* <span class="label label-default">v1.7.0</span> `--metrics_name_prefix`:
    Configure the prefix that is used when constructing metric names (default: marathon).
* <span class="label label-default">v1.7.0</span> `--metrics_prometheus`:
    Enable the StatsD reporter. Once enabled, metrics in the Prometheus
    format are available at `/metrics/prometheus`.
* <span class="label label-default">v1.7.0</span> `--metrics_statsd`:
    Enable the StatsD reporter.
* <span class="label label-default">v1.7.0</span> `--metrics_statsd_host`:
    Specify the host to push metrics to in the StatsD format.
* <span class="label label-default">v1.7.0</span> `--metrics_statsd_port`:
    Specify the port to push metrics to in the StatsD format.
* <span class="label label-default">v1.7.0</span> `--metrics_statsd_transmission_interval_ms`:
    Specify how often to push metrics to a StatsD endpoint (in milliseconds).
* <span class="label label-default">v1.7.0</span> `--metrics_datadog`:
    Enable the DataDog reporter.
* <span class="label label-default">v1.7.0</span> `--metrics_datadog_host`:
    Specify the host to push metrics to in the DataDog format.
* <span class="label label-default">v1.7.0</span> `--metrics_datadog_port`:
    Specify the port to push metrics to in the DataDog format.
* <span class="label label-default">v1.7.0</span> `--metrics_datadog_protocol`:
    Specify a protocol to use with the DataDog reporter: `udp` to send
    them over UDP to a DataDog agent, or `api` to send them directly to
    DataDog cloud using HTTP API (default: `udp`).
* <span class="label label-default">v1.7.0</span> `--metrics_datadog_transmission_interval_ms`:
    Specify how often to push metrics to a DataDog endpoint (in milliseconds).
* <span class="label label-default">v1.7.0</span> `--metrics_histogram_reservoir_significant_digits`:
    The number of significant decimal digits to which histograms and
    timers will maintain value resolution and separation (default: 4).
* <span class="label label-default">v1.7.0</span> `--metrics_histogram_reservoir_reset_periodically`:
    Clear histograms and timers fully according to the given interval
    (default: true).
* <span class="label label-default">v1.7.0</span> `--metrics_histogram_reservoir_resetting_interval_ms`:
    A histogram resetting interval in milliseconds (default: 5000).
* <span class="label label-default">v1.7.0</span> `--metrics_histogram_reservoir_resetting_chunks`:
    Histogram reservoirs are divided into this number of chunks, and one
    chunk is cleared after each (resetting interval / number of chunks)
    elapsed (default: 0). Increasing this will increase Marathon RAM
    footprint substantially (approximately a couple of hundred MB per
    chunk).

### Debug Flags

* <span class="label label-default">v0.8.2</span> `--logging_level` (Optional):
    Set the logging level of the application.
    Use one of `off`, `fatal`, `error`, `warn`, `info`, `debug`, `trace`, `all`.
* <span class="label label-default">v0.13.0</span> `--[disable_]tracing` (Optional. Default: disabled):
    Enable tracing for all service method calls.
    Log a trace message around the execution of every service method.
* <span class="label label-default">v1.2.0</span> `--logstash` (Optional. Default: disabled):
    Report logs over the network in JSON format as defined by the given endpoint in `(udp|tcp|ssl)://<host>:<port>` format.
