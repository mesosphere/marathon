package mesosphere.marathon.state

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin
import mesosphere.marathon.state.AppDefinition.VersionInfo

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

/**
  * A generic spec that specifies something that Marathon is able to launch instances of.
  */
trait RunnableSpec extends plugin.RunSpec {
  val id: PathId
  val env: Map[String, EnvVarValue]
  val labels: Map[String, String]
  val acceptedResourceRoles: Set[String]
  val secrets: Map[String, Secret]

  val instances: Int
  val constraints: Set[Constraint]

  // TODO: these could go into a resources object
  val cpus: Double
  val mem: Double
  val disk: Double
  val gpus: Int
  val version: Timestamp

  val isResident: Boolean

  def withInstances(instances: Int): RunnableSpec
  def isUpgrade(to: RunnableSpec): Boolean
  def needsRestart(to: RunnableSpec): Boolean
  def isOnlyScaleChange(to: RunnableSpec): Boolean
  val versionInfo: VersionInfo
}

//scalastyle:off
// TODO(PODS) why do we need RunSpec and RunnableSpec?!? can we also collapse some of these giant lists of
// fields into subtypes?
trait RunSpec extends plugin.RunSpec with RunnableSpec {
  val id: PathId
  val cmd: Option[String]
  val args: Seq[String]
  val user: Option[String]
  val env: Map[String, EnvVarValue]
  val instances: Int
  val cpus: Double
  val mem: Double
  val disk: Double
  val gpus: Int
  val executor: String
  val constraints: Set[Constraint]
  val fetch: Seq[FetchUri]
  val storeUrls: Seq[String]
  val portDefinitions: Seq[PortDefinition]
  val requirePorts: Boolean
  val backoff: FiniteDuration
  val backoffFactor: Double
  val maxLaunchDelay: FiniteDuration
  val container: Option[Container]
  val healthChecks: Set[HealthCheck]
  val readinessChecks: Seq[ReadinessCheck]
  val taskKillGracePeriod: Option[FiniteDuration]
  val dependencies: Set[PathId]
  val upgradeStrategy: UpgradeStrategy
  val labels: Map[String, String]
  val acceptedResourceRoles: Set[String]
  val ipAddress: Option[IpAddress]
  val versionInfo: VersionInfo
  val version: Timestamp
  val residency: Option[Residency]
  val isResident: Boolean
  val secrets: Map[String, Secret]
  val isSingleInstance: Boolean
  val volumes: Seq[Volume]
  val persistentVolumes: Seq[PersistentVolume]
  val externalVolumes: Seq[ExternalVolume]
  val diskForPersistentVolumes: Double
  val portNumbers: Seq[Int]
  val portNames: Seq[String]
  val servicePorts: Seq[Int]
  def portAssignments(task: Task): Seq[PortAssignment]
}
