package mesosphere.marathon

import mesosphere.marathon.core.deployment.DeploymentConfig
import mesosphere.marathon.core.event.EventConf
import mesosphere.marathon.core.flow.{ LaunchTokenConfig, ReviveOffersConfig }
import mesosphere.marathon.core.heartbeat.MesosHeartbeatMonitor
import mesosphere.marathon.core.group.GroupManagerConfig
import mesosphere.marathon.core.launcher.OfferProcessorConfig
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.plugin.PluginManagerConfiguration
import mesosphere.marathon.core.task.jobs.TaskJobsConfig
import mesosphere.marathon.core.task.termination.KillConfig
import mesosphere.marathon.core.task.tracker.InstanceTrackerConfig
import mesosphere.marathon.core.task.update.TaskStatusUpdateConfig
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.ResourceRole
import mesosphere.marathon.storage.StorageConf
import org.rogach.scallop.ScallopConf

import scala.sys.SystemProperties

/**
  * We have to cache in a separated object because [[mesosphere.marathon.MarathonConf]] is a trait and thus every
  * instance would call {{{java.net.InetAddress.getLocalHost}}} which is blocking. We want to call it only once.
  * Note: This affect mostly tests.
  */
private[marathon] object MarathonConfHostNameCache {

  lazy val hostname = java.net.InetAddress.getLocalHost.getHostName
}

trait MarathonConf
    extends ScallopConf
    with EventConf with GroupManagerConfig with LaunchQueueConfig with LaunchTokenConfig with LeaderProxyConf
    with MarathonSchedulerServiceConfig with OfferMatcherManagerConfig with OfferProcessorConfig
    with PluginManagerConfiguration with ReviveOffersConfig with StorageConf with KillConfig
    with TaskJobsConfig with TaskStatusUpdateConfig with InstanceTrackerConfig with DeploymentConfig with ZookeeperConf {

  lazy val mesosMaster = opt[String](
    "master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  lazy val mesosLeaderUiUrl = opt[String](
    "mesos_leader_ui_url",
    descr = "The host and port (e.g. \"http://mesos_host:5050\") of the Mesos master.",
    required = false,
    noshort = true)

  lazy val features = opt[String](
    "enable_features",
    descr = s"A comma-separated list of features. Available features are: ${Features.description}",
    required = false,
    default = None,
    noshort = true,
    validate = validateFeatures
  )

  override lazy val availableFeatures: Set[String] = features.get.map(parseFeatures).getOrElse(Set.empty)

  private[this] def parseFeatures(str: String): Set[String] =
    str.split(',').map(_.trim).filter(_.nonEmpty).toSet

  private[this] def validateFeatures(str: String): Boolean = {
    val parsed = parseFeatures(str)
    // throw exceptions for better error messages
    val unknownFeatures = parsed.filter(!Features.availableFeatures.contains(_))
    lazy val unknownFeaturesString = unknownFeatures.mkString(", ")
    require(
      unknownFeatures.isEmpty,
      s"Unknown features specified: $unknownFeaturesString. Available features are: ${Features.description}"
    )
    true
  }

  def isFeatureSet(name: String): Boolean = availableFeatures.contains(name)

  lazy val mesosFailoverTimeout = opt[Long](
    "failover_timeout",
    descr = "(Default: 1 week) The failover_timeout for mesos in seconds.",
    default = Some(604800L))

  lazy val highlyAvailable = toggle(
    "ha",
    descrYes = "(Default) Run Marathon in HA mode with leader election. " +
      "Allows starting an arbitrary number of other Marathons but all need " +
      "to be started in HA mode. This mode requires a running ZooKeeper",
    descrNo = "Run Marathon in single node mode.",
    prefix = "disable_",
    noshort = true,
    default = Some(true))

  lazy val checkpoint = toggle(
    "checkpoint",
    descrYes = "(Default) Enable checkpointing of tasks. " +
      "Requires checkpointing enabled on slaves. Allows tasks to continue " +
      "running during mesos-slave restarts and upgrades",
    descrNo = "Disable checkpointing of tasks.",
    prefix = "disable_",
    noshort = true,
    default = Some(true))

  lazy val localPortMin = opt[Int](
    "local_port_min",
    descr = "Min port number to use when assigning globally unique service ports to apps.",
    default = Some(10000))

  lazy val localPortMax = opt[Int](
    "local_port_max",
    descr = "Max port number to use when assigning globally unique service ports to apps.",
    default = Some(20000))

  lazy val defaultExecutor = opt[String](
    "executor",
    descr = "Executor to use when none is specified. If not defined the Mesos command executor is used by default.",
    default = Some("//cmd"))

  lazy val hostname = opt[String](
    "hostname",
    descr = "The advertised hostname that is used for the communication with the Mesos master. " +
      "The value is also stored in the persistent store so another standby host can redirect to the elected leader.",
    default = Some(MarathonConfHostNameCache.hostname))

  lazy val webuiUrl = opt[String](
    "webui_url",
    descr = "The HTTP(S) url of the web ui, defaulting to the advertised hostname.",
    noshort = true,
    default = None)

  lazy val maxConcurrentHttpConnections = opt[Int](
    "http_max_concurrent_requests",
    descr = "The number of concurrent HTTP requests that are allowed before rejecting.",
    noshort = true,
    default = None
  )

  lazy val accessControlAllowOrigin = opt[String](
    "access_control_allow_origin",
    descr = "The origin(s) to allow in Marathon. Not set by default. " +
      "Example values are \"*\", or " +
      "\"http://localhost:8888, http://domain.com\"",
    noshort = true,
    default = None)

  def executor: Executor = Executor.dispatch(defaultExecutor())

  lazy val mesosRole = opt[String](
    "mesos_role",
    descr = "Mesos role for this framework. " +
      "If set, Marathon receives resource offers for the specified role in addition to " +
      "resources with the role designation '*'.",
    default = None)

  def expectedResourceRoles: Set[String] = mesosRole.get match {
    case Some(role) => Set(role, ResourceRole.Unreserved)
    case None => Set(ResourceRole.Unreserved)
  }

  lazy val defaultAcceptedResourceRolesSet = defaultAcceptedResourceRoles.get.getOrElse(expectedResourceRoles)

  lazy val defaultAcceptedResourceRoles = opt[String](
    "default_accepted_resource_roles",
    descr =
      "Default for the defaultAcceptedResourceRoles attribute of all app definitions" +
        " as a comma-separated list of strings. " +
        "This defaults to all roles for which this Marathon instance is configured to receive offers.",
    default = None,
    validate = validateDefaultAcceptedResourceRoles).map(parseDefaultAcceptedResourceRoles)

  private[this] def parseDefaultAcceptedResourceRoles(str: String): Set[String] =
    str.split(',').map(_.trim).toSet

  private[this] def validateDefaultAcceptedResourceRoles(str: String): Boolean = {
    val parsed = parseDefaultAcceptedResourceRoles(str)

    // throw exceptions for better error messages
    require(parsed.nonEmpty, "--default_accepted_resource_roles must not be empty")
    require(
      parsed.forall(expectedResourceRoles),
      "--default_accepted_resource_roles contains roles for which we will not receive offers: " +
        (parsed -- expectedResourceRoles).mkString(", "))

    true
  }

  lazy val taskLaunchConfirmTimeout = opt[Long](
    "task_launch_confirm_timeout",
    descr = "Time, in milliseconds, to wait for a task to enter " +
      "the TASK_STAGING state before killing it.",
    default = Some(300000L))

  lazy val taskLaunchTimeout = opt[Long](
    "task_launch_timeout",
    descr = "Time, in milliseconds, to wait for a task to enter " +
      "the TASK_RUNNING state before killing it.",
    default = Some(300000L)) // 300 seconds (5 minutes)

  lazy val taskReservationTimeout = opt[Long](
    "task_reservation_timeout",
    descr = "Time, in milliseconds, to wait for a new reservation to be acknowledged " +
      "via a received offer before deleting it.",
    default = Some(20000L)) // 20 seconds

  lazy val reconciliationInitialDelay = opt[Long](
    "reconciliation_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically perform task reconciliation operations",
    default = Some(15000L)) // 15 seconds

  lazy val reconciliationInterval = opt[Long](
    "reconciliation_interval",
    descr = "This is the length of time, in milliseconds, between task " +
      "reconciliation operations.",
    default = Some(600000L)) // 600 seconds (10 minutes)

  lazy val scaleAppsInitialDelay = opt[Long](
    "scale_apps_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
      "begins to periodically attempt to scale apps.",
    default = Some(15000L)) // 15 seconds

  lazy val scaleAppsInterval = opt[Long](
    "scale_apps_interval",
    descr = "This is the length of time, in milliseconds, between task " +
      "scale apps.",
    default = Some(300000L)) // 300 seconds (5 minutes)

  lazy val mesosUser = opt[String](
    "mesos_user",
    descr = "Mesos user for this framework.",
    default = new SystemProperties().get("user.name")) // Current logged in user

  lazy val frameworkName = opt[String](
    "framework_name",
    descr = "Framework name to register with Mesos.",
    default = Some("marathon"))

  lazy val artifactStore = opt[String](
    "artifact_store",
    descr = "URL to the artifact store. " +
      s"""Supported store types ${StorageProvider.examples.keySet.mkString(", ")}. """ +
      s"""Example: ${StorageProvider.examples.values.mkString(", ")}""",
    validate = StorageProvider.isValidUrl,
    noshort = true
  )

  lazy val mesosAuthentication = toggle(
    "mesos_authentication",
    default = Some(false),
    noshort = true,
    descrYes = "Will use framework authentication while registering with Mesos with principal and optional secret.",
    descrNo = "(Default) will not use framework authentication while registering with Mesos.",
    prefix = "disable_"
  )
  dependsOnAll(mesosAuthentication, List(mesosAuthenticationPrincipal))

  lazy val mesosAuthenticationPrincipal = opt[String](
    "mesos_authentication_principal",
    descr = "Mesos Authentication Principal.",
    noshort = true
  )

  lazy val mesosAuthenticationSecretFile = opt[String](
    "mesos_authentication_secret_file",
    descr = "Path to a file containing the Mesos Authentication Secret.",
    noshort = true
  )
  lazy val mesosAuthenticationSecret = opt[String](
    "mesos_authentication_secret",
    descr = "Mesos Authentication Secret.",
    noshort = true
  )
  mutuallyExclusive(mesosAuthenticationSecret, mesosAuthenticationSecretFile)

  lazy val envVarsPrefix = opt[String](
    "env_vars_prefix",
    descr = "Prefix to use for environment variables injected automatically into all started tasks.",
    noshort = true
  )

  lazy val defaultNetworkName = opt[String](
    "default_network_name",
    descr = "Network name, injected into applications' container-mode network{} specs that do not define their own name.",
    noshort = true
  )

  //Internal settings, that are not intended for external use

  lazy val maxApps = opt[Int](
    "max_apps",
    descr = "The maximum number of applications that may be created.",
    noshort = true
  )

  lazy val onElectedPrepareTimeout = opt[Long] (
    "on_elected_prepare_timeout",
    descr = "The timeout for preparing the Marathon instance when elected as leader.",
    default = Some(3 * 60 * 1000L) //3 minutes
  )

  lazy val leaderElectionBackend = opt[String](
    "leader_election_backend",
    descr = "The backend for leader election to use.",
    hidden = true,
    validate = Set("curator").contains,
    default = Some("curator")
  )

  lazy val mesosHeartbeatInterval = opt[Long](
    "mesos_heartbeat_interval",
    descr = "(milliseconds) in the absence of receiving a message from the mesos master " +
      "during a time window of this duration, attempt to coerce mesos into communicating with marathon.",
    noshort = true,
    hidden = true,
    default = Some(MesosHeartbeatMonitor.DEFAULT_HEARTBEAT_INTERVAL_MS))

  lazy val mesosHeartbeatFailureThreshold = opt[Int](
    "mesos_heartbeat_failure_threshold",
    descr = "after missing this number of expected communications from the mesos master, " +
      "infer that marathon has become disconnected from the master.",
    noshort = true,
    hidden = true,
    default = Some(MesosHeartbeatMonitor.DEFAULT_HEARTBEAT_FAILURE_THRESHOLD))
}

