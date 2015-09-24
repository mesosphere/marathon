package mesosphere.marathon

import mesosphere.marathon.core.flow.{ ReviveOffersConfig, LaunchTokenConfig }
import mesosphere.marathon.core.launcher.OfferProcessorConfig
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.plugin.PluginConfiguration
import org.rogach.scallop.ScallopConf
import scala.sys.SystemProperties

import mesosphere.marathon.io.storage.StorageProvider

trait MarathonConf
    extends ScallopConf with ZookeeperConf with LeaderProxyConf
    with LaunchTokenConfig with OfferMatcherManagerConfig with OfferProcessorConfig with ReviveOffersConfig
    with MarathonSchedulerServiceConfig with LaunchQueueConfig with PluginConfiguration {

  //scalastyle:off magic.number

  lazy val mesosMaster = opt[String]("master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  lazy val mesosLeaderUiUrl = opt[String]("mesos_leader_ui_url",
    descr = "The host and port (e.g. \"http://mesos_host:5050\") of the Mesos master",
    required = false,
    noshort = true)

  lazy val mesosFailoverTimeout = opt[Long]("failover_timeout",
    descr = "The failover_timeout for mesos in seconds (default: 1 week)",
    default = Some(604800L))

  lazy val highlyAvailable = toggle("ha",
    descrYes = "(Default) Run Marathon in HA mode with leader election. " +
      "Allows starting an arbitrary number of other Marathons but all need " +
      "to be started in HA mode. This mode requires a running ZooKeeper",
    descrNo = "Run Marathon in single node mode.",
    prefix = "disable_",
    noshort = true,
    default = Some(true))

  lazy val checkpoint = toggle("checkpoint",
    descrYes = "(Default) Enable checkpointing of tasks. " +
      "Requires checkpointing enabled on slaves. Allows tasks to continue " +
      "running during mesos-slave restarts and upgrades",
    descrNo = "Disable checkpointing of tasks.",
    prefix = "disable_",
    noshort = true,
    default = Some(true))

  lazy val localPortMin = opt[Int]("local_port_min",
    descr = "Min port number to use when assigning globally unique service ports to apps",
    default = Some(10000))

  lazy val localPortMax = opt[Int]("local_port_max",
    descr = "Max port number to use when assigning globally unique service ports to apps",
    default = Some(20000))

  lazy val defaultExecutor = opt[String]("executor",
    descr = "Executor to use when none is specified",
    default = Some("//cmd"))

  lazy val hostname = opt[String]("hostname",
    descr = "The advertised hostname that is used for the communication with the mesos master. " +
      "The value is also stored in the persistent store so another standby host can redirect to the elected leader.",
    default = Some(java.net.InetAddress.getLocalHost.getHostName))

  lazy val webuiUrl = opt[String]("webui_url",
    descr = "The http(s) url of the web ui, defaulting to the advertised hostname",
    noshort = true,
    default = None)

  lazy val maxConcurrentHttpConnections = opt[Int]("http_max_concurrent_requests",
    descr = "The number of concurrent http requests, that are allowed before rejecting.",
    noshort = true,
    default = None
  )

  lazy val accessControlAllowOrigin = opt[String]("access_control_allow_origin",
    descr = "The origin(s) to allow in Marathon. Not set by default. " +
      "Example values are \"*\", or " +
      "\"http://localhost:8888, http://domain.com\"",
    noshort = true,
    default = None)

  lazy val eventStreamMaxOutstandingMessages = opt[Int]("event_stream_max_outstanding_messages",
    descr = "The event stream buffers events, that are not already consumed by clients. " +
      "This number defines the number of events that get buffered on the server side, before messages are dropped.",
    noshort = true,
    default = Some(50)
  )

  def executor: Executor = Executor.dispatch(defaultExecutor())

  lazy val mesosRole = opt[String]("mesos_role",
    descr = "Mesos role for this framework. " +
      "If set, Marathon receives resource offers for the specified role in addition to " +
      "resources with the role designation '*'.",
    default = None)

  def expectedResourceRoles: Set[String] = mesosRole.get match {
    case Some(role) => Set(role, "*")
    case None       => Set("*")
  }

  lazy val defaultAcceptedResourceRolesSet = defaultAcceptedResourceRoles.get.getOrElse(expectedResourceRoles)

  lazy val defaultAcceptedResourceRoles = opt[String]("default_accepted_resource_roles",
    descr =
      "Default for the defaultAcceptedResourceRoles attribute of all app definitions" +
        " as a comma-separated list of strings." +
        "This defaults to all roles for which this Marathon instance is configured to receive offers.",
    default = None,
    validate = validateDefaultAcceptedResourceRoles).map(parseDefaultAcceptedResourceRoles)

  private[this] def parseDefaultAcceptedResourceRoles(str: String): Set[String] =
    str.split(',').map(_.trim).toSet

  private[this] def validateDefaultAcceptedResourceRoles(str: String): Boolean = {
    val parsed = parseDefaultAcceptedResourceRoles(str)

    // throw exceptions for better error messages
    require(parsed.nonEmpty, "--default_accepted_resource_roles must not be empty")
    require(parsed.forall(expectedResourceRoles),
      "--default_accepted_resource_roles contains roles for which we will not receive offers: " +
        (parsed -- expectedResourceRoles).mkString(", "))

    true
  }

  lazy val taskLaunchConfirmTimeout = opt[Long]("task_launch_confirm_timeout",
    descr = "Time, in milliseconds, to wait for a task to enter " +
      "the TASK_STAGING state before killing it.",
    default = Some(10000L))

  lazy val taskLaunchTimeout = opt[Long]("task_launch_timeout",
    descr = "(deprecated) Time, in milliseconds, to wait for a task to enter " +
      "the TASK_RUNNING state before killing it. NOTE: this is a temporary " +
      "fix for MESOS-1922. This option will be removed in a later release.",
    default = Some(300000L)) // 300 seconds (5 minutes)

  lazy val reconciliationInitialDelay = opt[Long]("reconciliation_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically perform task reconciliation operations",
    default = Some(15000L)) // 15 seconds

  lazy val reconciliationInterval = opt[Long]("reconciliation_interval",
    descr = "This is the length of time, in milliseconds, between task " +
      "reconciliation operations.",
    default = Some(300000L)) // 300 seconds (5 minutes)

  lazy val scaleAppsInitialDelay = opt[Long]("scale_apps_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically attempt to scale apps",
    default = Some(15000L)) // 15 seconds

  lazy val scaleAppsInterval = opt[Long]("scale_apps_interval",
    descr = "This is the length of time, in milliseconds, between task " +
      "scale apps.",
    default = Some(300000L)) // 300 seconds (5 minutes)

  lazy val marathonStoreTimeout = opt[Long]("marathon_store_timeout",
    descr = "Maximum time, in milliseconds, to wait for persistent storage " +
      "operations to complete.",
    default = Some(2000L)) // 2 seconds

  lazy val mesosUser = opt[String]("mesos_user",
    descr = "Mesos user for this framework",
    default = new SystemProperties().get("user.name")) // Current logged in user

  lazy val frameworkName = opt[String]("framework_name",
    descr = "Framework name to register with Mesos.",
    default = Some("marathon"))

  lazy val artifactStore = opt[String]("artifact_store",
    descr = "URL to the artifact store. " +
      s"""Supported store types ${StorageProvider.examples.keySet.mkString(", ")}. """ +
      s"""Example: ${StorageProvider.examples.values.mkString(", ")}""",
    validate = StorageProvider.isValidUrl,
    noshort = true
  )

  lazy val mesosAuthenticationPrincipal = opt[String]("mesos_authentication_principal",
    descr = "Mesos Authentication Principal",
    noshort = true
  )

  lazy val mesosAuthenticationSecretFile = opt[String]("mesos_authentication_secret_file",
    descr = "Mesos Authentication Secret",
    noshort = true
  )

  lazy val envVarsPrefix = opt[String]("env_vars_prefix",
    descr = "Prefix to use for environment variables",
    noshort = true
  )

  //Internal settings, that are not intended for external use
  lazy val internalStoreBackend = opt[String]("internal_store_backend",
    descr = "The backend storage system to use. One of zk, mesos_zk, mem",
    hidden = true,
    validate = Set("zk", "mesos_zk", "mem").contains,
    default = Some("zk")
  )
}
