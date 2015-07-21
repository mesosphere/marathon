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
* `--checkpoint` (Optional. Default: true): Enable checkpointing of tasks.
    Requires checkpointing enabled on slaves. Allows tasks to continue running
    during mesos-slave restarts and Marathon scheduler failover.  See the
    description of `--failover_timeout`.
* `--executor` (Optional. Default: "//cmd"): Executor to use when none is
    specified.
* `--failover_timeout` (Optional. Default: 604800 seconds (1 week)): The
    failover_timeout for Mesos in seconds.  If a new Marathon instance has
    not re-registered with Mesos this long after a failover, Mesos will shut
    down all running tasks started by Marathon.  Requires checkpointing to be
    enabled.
* `--framework_name` (Optional. Default: marathon-VERSION): The framework name
    to register with Mesos.
* `--ha` (Optional. Default: true): Runs Marathon in HA mode with leader election.
    Allows starting an arbitrary number of other Marathons but all need to be
    started in HA mode. This mode requires a running ZooKeeper. See `--master`.
* `--hostname` (Optional. Default: hostname of machine): The advertised hostname
    stored in ZooKeeper so another standby host can redirect to the elected leader.
    _Note: Default is determined by
    [`InetAddress.getLocalHost`](http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getLocalHost())._
* `--webui_url` (Optional. Default: None): The url of the Marathon web ui. It
    is passed to Mesos to be used in links back to the Marathon UI. If not set,
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
* `--reconciliation_interval` (Optional. Default: 300000 (5 minutes)): The
    period, in milliseconds, between task reconciliation operations.
* `--scale_apps_initial_delay` (Optional. Default: 15000 (15 seconds)): The
    delay, in milliseconds, before Marathon begins to periodically perform
    application scaling operations.
* `--scale_apps_interval` (Optional. Default: 300000 (5 minutes)): The period,
    in milliseconds, between application scaling operations.
* `--task_launch_timeout` **DEPRECATED** (Optional. Default: 300000 (5 minutes)):
    Time, in milliseconds, to wait for a task to enter the TASK_RUNNING state
    before killing it. _Note: This is a temporary fix for MESOS-1922.
    This option will be removed in a later release._
* `--event_subscriber` (Optional. Default: None): Event subscriber module to
    enable. Currently the only valid value is `http_callback`.
* `--http_endpoints` (Optional. Default: None): Pre-configured http callback
    URLs. Valid only in conjunction with `--event_subscriber http_callback`.
    Additional callback URLs may also be set dynamically via the REST API.
* `--zk` (Optional. Default: None): ZooKeeper URL for storing state.
    Format: `zk://host1:port1,host2:port2,.../path`
* `--zk_max_versions` (Optional. Default: None): Limit the number of versions
    stored for one entity.
* `--zk_timeout` (Optional. Default: 10000 (10 seconds)): Timeout for ZooKeeper
    in milliseconds.
*  <span class="label label-default">v0.9.0</span> `--zk_session_timeout` (Optional. Default: 1.800.000 (30 minutes)): Timeout for ZooKeeper
    sessions in milliseconds.
* `--mesos_authentication_principal` (Optional.): The Mesos principal used for
    authentication
* `--mesos_authentication_secret_file` (Optional.): The path to the Mesos secret
    file containing the authentication secret
* `--marathon_store_timeout` (Optional. Default: 2000 (2 seconds)): Maximum time
    in milliseconds, to wait for persistent storage operations to complete.
* <span class="label label-default">v0.10.0</span> `--env_vars_prefix` (Optional. Default: None):
    The prefix to add to the name of task's environment variables created
    automatically by Marathon.
    _Note: This prefix will not be added to variables that are already prefixed,
    such as `MESOS_TASK_ID` and `MARATHON_APP_ID`

## Tuning Flags for Offer Matching/Launching Tasks

Mesos frequently sends resource offers to Marathon (and all other frameworks). Each offer will represent the
available resources of a single node in the cluster. Before this <span class="label label-default">v0.8.2</span>,
Marathon would only start a single task per
resource offer, which led to slow task launching in smaller clusters. In order to speed up task launching and use the
resource offers Marathon receives from Mesos more efficiently, we added a new offer matching algorithm which tries
to start as many tasks as possible per task offer cycle. The maximum number of tasks to start is configurable with
the following startup parameters:

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer` (Optional. Default: 1): Launch at most this
    number of tasks per Mesos offer. Usually,
    there is one offer per cycle and slave. You can speed up launching tasks by increasing this number.

* <span class="label label-default">v0.8.2</span> `--max_tasks_per_offer_cycle` (Optional. Default: 1000): Launch at
    most this number of tasks per Mesos offer cycle.
    A larger value speeds up launching new tasks.
    Yet, choosing a too large value might overwhelm Mesos/Marathon with processing task updates.

**Example**

Given a cluster with 200 nodes and the default settings for task launching. If we want to start 2000 tasks, it would
take at least 10 cycles, because we are only starting 1 task per offer, leading to a total maximum of 200. If we
change the `max_tasks_per_offer` setting to 10, we could start 1000 tasks per offer (the default setting for
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
*  <span class="label label-default">v0.10.0</span> `--http_max_concurrent_requests` (Optional.): the maximum number of
    concurrent http requests, that is allowed concurrently before requests get answered directly with a
    HTTP 503 Service Temporarily Unavailable.
    



### Debug Flags

* <span class="label label-default">v0.8.2</span> `--logging_level` (Optional.):
    Set the logging level of the application.
    Use one of `off`, `fatal`, `error`, `warn`, `info`, `debug`, `trace`, `all`.
* <span class="label label-default">Deprecated</span> `--enable_metrics` (Optional.):
    Enable metrics for all service method calls.
    The execution time per method is available via the metrics endpoint.
    This option is turned on by default since version 0.10.0.
    To turn this feature off, you can use `--disable_metrics`.
* <span class="label label-default">v0.10.0</span> `--disable_metrics` (Optional.):
    Metrics are enabled by default, so that the execution time per method is available via the metrics endpoint.
    This metrics measurement can be disabled with this option.
* <span class="label label-default">v0.8.2</span> `--enable_tracing` (Optional.):
    Enable tracing for all service method calls.
    Around the execution of every service method a trace log message is issued.
    
