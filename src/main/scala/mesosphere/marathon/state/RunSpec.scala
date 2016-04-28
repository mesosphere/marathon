package mesosphere.marathon.state

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.Container.Docker.PortMapping

import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Seq

//scalastyle:off
trait RunSpec {
  def id: PathId

  def cmd: Option[String]

  def args: Option[Seq[String]]

  def user: Option[String]

  def env: Map[String, String]

  def instances: Int

  def cpus: Double

  def mem: Double

  def disk: Double

  def executor: String

  def constraints: Set[Constraint]

  def fetch: Seq[FetchUri]

  def storeUrls: Seq[String]

  def portDefinitions: Seq[PortDefinition]

  def requirePorts: Boolean

  def backoff: FiniteDuration

  def backoffFactor: Double

  def maxLaunchDelay: FiniteDuration

  def container: Option[Container]

  def healthChecks: Set[HealthCheck]

  def readinessChecks: Seq[ReadinessCheck]

  def dependencies: Set[PathId]

  def upgradeStrategy: UpgradeStrategy

  def labels: Map[String, String]

  def acceptedResourceRoles: Option[Set[String]]

  def ipAddress: Option[IpAddress]

  def versionInfo: VersionInfo

  def version: Timestamp

  def residency: Option[Residency]

  def isResident: Boolean

  def isUpgrade(to: RunSpec): Boolean

  def needsRestart(to: RunSpec): Boolean

  def isOnlyScaleChange(to: RunSpec): Boolean

  def isSingleInstance: Boolean
  def volumes: Iterable[Volume]
  def persistentVolumes: Iterable[PersistentVolume]
  def externalVolumes: Iterable[ExternalVolume]
  def diskForPersistentVolumes: Double
  def portNumbers: Seq[Int]
  def portNames: Seq[String]
  def containerHostPorts: Option[Seq[Int]]
  def portMappings: Option[Seq[PortMapping]]
  def servicePorts: Seq[Int]
  def portAssignments(task: Task): Option[Seq[PortAssignment]]
}
