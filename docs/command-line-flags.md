---
title: Command Line Flags
---

# Marathon Command Line Flags

The following options can influence how Marathon works:

*All options can be also set by environment variable `MARATON_OPTION_NAME` (the option name with a `MARATHON_` prefix added to it), for example `MARATHON_MASTER` for `--master` option.  Please note that command line options precede environment variables.  This means that if the `MARATHON_MASTER` environment variable is set and `--master` is supplied on the command line, then the environment variable is ignored.*

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
* `--checkpoint` (Optional. Default: false): Enable checkpointing of tasks.
    Requires checkpointing enabled on slaves. Allows tasks to continue running
    during mesos-slave restarts and upgrades.
* `--executor` (Optional. Default: "//cmd"): Executor to use when none is
    specified.
* `--failover_timeout` (Optional. Default: 604800 seconds (1 week)): The
    failover_timeout for Mesos in seconds.
* `--ha` (Optional. Default: true): Runs Marathon in HA mode with leader election.
    Allows starting an arbitrary number of other Marathons but all need to be
    started in HA mode. This mode requires a running ZooKeeper. See `--master`.
* `--hostname` (Optional. Default: hostname of machine): The advertised hostname
    stored in ZooKeeper so another standby host can redirect to the elected leader.
    _Note: Default is determined by
    [`InetAddress.getLocalHost`](http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getLocalHost())._
* `--local_port_max` (Optional. Default: 20000): Max port number to use when
    assigning ports to apps.
* `--local_port_min` (Optional. Default: 10000): Min port number to use when
    assigning ports to apps.
* `--mesos_role` (Optional. Default: None): Mesos role for this framework.
* `--mesos_user` (Optional. Default: current user): Mesos user for
    this framework. _Note: Default is determined by
    [`SystemProperties.get("user.name")`](http://www.scala-lang.org/api/current/index.html#scala.sys.SystemProperties@get\(key:String\):Option[String])._
* `--reconciliation_initial_delay` (Optional. Default: 30000 (30 seconds)): The
    delay, in milliseconds, before Marathon begins to periodically perform task
    reconciliation operations.
* `--reconciliation_interval` (Optional. Default: 30000 (30 seconds)): The
    period, in milliseconds, between task reconciliation operations.
* `--task_launch_timeout` (Optional. Default: 60000 (60 seconds)): Time,
    in milliseconds, to wait for a task to enter the TASK_RUNNING state before
    killing it.
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
* `--mesos_authentication_principal` (Optional.): The Mesos principal used for
    authentication
* `--mesos_authentication_secret_file` (Optional.): The path to the Mesos secret
    file containing the authentication secret

### Optional Flags Inherited from [Chaos](https://github.com/mesosphere/chaos)

* `--assets_path` (Optional. Default: None): Local file system path from which
    to load assets for the web UI. If not supplied, assets are loaded from the
    packaged JAR.
* `--http_credentials` (Optional. Default: None): Credentials for accessing the
    HTTP service in the format of `username:password`. The username may not
    contain a colon (:).
* `--http_port` (Optional. Default: 8080): The port on which to listen for HTTP
    requests.
* `--https_port` (Optional. Default: 8443): The port on which to listen for
    HTTPS requests.
* `--ssl_keystore_password` (Optional. Default: None): Password for the keystore
    supplied with the `ssl_keystore_path` option.
* `--ssl_keystore_path` (Optional. Default: None): Path to the SSL keystore. SSL
    will be enabled if this option is supplied.
