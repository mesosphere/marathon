package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble, Integer => JInt }

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidV2AppDefinition }
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state.AppDefinition.VersionInfo.FullVersionInfo
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

@PortIndices
@ValidV2AppDefinition
case class V2AppDefinition(

    id: PathId = AppDefinition.DefaultId,

    cmd: Option[String] = AppDefinition.DefaultCmd,

    args: Option[Seq[String]] = AppDefinition.DefaultArgs,

    user: Option[String] = AppDefinition.DefaultUser,

    env: Map[String, String] = AppDefinition.DefaultEnv,

    @FieldMin(0) instances: JInt = AppDefinition.DefaultInstances,

    cpus: JDouble = AppDefinition.DefaultCpus,

    mem: JDouble = AppDefinition.DefaultMem,

    disk: JDouble = AppDefinition.DefaultDisk,

    @FieldPattern(regexp = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$") executor: String = AppDefinition.DefaultExecutor,

    constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

    uris: Seq[String] = AppDefinition.DefaultUris,

    storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

    @FieldPortsArray ports: Seq[JInt] = AppDefinition.DefaultPorts,

    requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

    backoff: FiniteDuration = AppDefinition.DefaultBackoff,

    backoffFactor: JDouble = AppDefinition.DefaultBackoffFactor,

    maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

    container: Option[Container] = AppDefinition.DefaultContainer,

    healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

    dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

    upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

    labels: Map[String, String] = AppDefinition.DefaultLabels,

    acceptedResourceRoles: Option[Set[String]] = None,

    version: Timestamp = Timestamp.now(),

    versionInfo: Option[V2AppDefinition.VersionInfo] = None) extends Timestamped {

  assert(
    portIndicesAreValid(),
    "Health check port indices must address an element of the ports array or container port mappings."
  )

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean =
    this.toAppDefinition.portIndicesAreValid()

  /**
    * Returns the canonical internal representation of this API-specific
    * application defintion.
    */
  def toAppDefinition: AppDefinition = {
    val appVersionInfo = versionInfo match {
      case Some(V2AppDefinition.VersionInfo(lastScalingAt, lastChangeAt)) =>
        AppDefinition.VersionInfo.FullVersionInfo(version, lastScalingAt, lastChangeAt)
      case None =>
        AppDefinition.VersionInfo.OnlyVersion(version)
    }
    AppDefinition(
      id = id, cmd = cmd, args = args, user = user, env = env, instances = instances, cpus = cpus,
      mem = mem, disk = disk, executor = executor, constraints = constraints, uris = uris,
      storeUrls = storeUrls, ports = ports, requirePorts = requirePorts, backoff = backoff,
      backoffFactor = backoffFactor, maxLaunchDelay = maxLaunchDelay, container = container,
      healthChecks = healthChecks, dependencies = dependencies, upgradeStrategy = upgradeStrategy,
      labels = labels, acceptedResourceRoles = acceptedResourceRoles,
      versionInfo = appVersionInfo
    )
  }

  def withTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): V2AppDefinition.WithTaskCountsAndDeployments = {
    new V2AppDefinition.WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, this)
  }

  def withTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): V2AppDefinition.WithTasksAndDeployments =
    new V2AppDefinition.WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, this)

  def withTasksAndDeploymentsAndFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure]): V2AppDefinition.WithTasksAndDeploymentsAndTaskFailures =
    new V2AppDefinition.WithTasksAndDeploymentsAndTaskFailures(
      appTasks, healthCounts,
      runningDeployments, taskFailure, this
    )

  def withCanonizedIds(base: PathId = PathId.empty): V2AppDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId, dependencies = dependencies.map(_.canonicalPath(baseId)))
  }
}

object V2AppDefinition {

  case class VersionInfo(
    lastScalingAt: Timestamp,
    lastConfigChangeAt: Timestamp)

  def apply(app: AppDefinition): V2AppDefinition = {
    val maybeVersionInfo = app.versionInfo match {
      case FullVersionInfo(_, lastScalingAt, lastConfigChangeAt) => Some(VersionInfo(lastScalingAt, lastConfigChangeAt))
      case _ => None
    }

    V2AppDefinition(
      id = app.id, cmd = app.cmd, args = app.args, user = app.user, env = app.env, instances = app.instances,
      cpus = app.cpus,
      mem = app.mem, disk = app.disk, executor = app.executor, constraints = app.constraints, uris = app.uris,
      storeUrls = app.storeUrls, ports = app.ports, requirePorts = app.requirePorts, backoff = app.backoff,
      backoffFactor = app.backoffFactor, maxLaunchDelay = app.maxLaunchDelay, container = app.container,
      healthChecks = app.healthChecks, dependencies = app.dependencies, upgradeStrategy = app.upgradeStrategy,
      labels = app.labels, acceptedResourceRoles = app.acceptedResourceRoles,
      version = app.version,
      versionInfo = maybeVersionInfo
    )
  }

  protected[marathon] class WithTaskCountsAndDeployments(
      appTasks: Seq[EnrichedTask],
      healthCounts: HealthCounts,
      runningDeployments: Seq[DeploymentPlan],
      val app: V2AppDefinition) {
    /**
      * Snapshot of the number of staged (but not running) tasks
      * for this app
      */
    val tasksStaged: Int = appTasks.count { eTask =>
      eTask.task.getStagedAt != 0 && eTask.task.getStartedAt == 0
    }

    /**
      * Snapshot of the number of running tasks for this app
      */
    val tasksRunning: Int = appTasks.count { eTask =>
      eTask.task.hasStatus &&
        eTask.task.getStatus.getState == mesos.TaskState.TASK_RUNNING
    }

    /**
      * Snapshot of the number of healthy tasks for this app
      */
    val tasksHealthy: Int = healthCounts.healthy

    /**
      * Snapshot of the number of unhealthy tasks for this app
      */
    val tasksUnhealthy: Int = healthCounts.unhealthy

    /**
      * Snapshot of the running deployments that affect this app
      */
    def deployments: Seq[Identifiable] = {
      runningDeployments.collect {
        case plan: DeploymentPlan if plan.affectedApplicationIds contains app.id => Identifiable(plan.id)
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    app: V2AppDefinition)
      extends WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    def tasks: Seq[EnrichedTask] = appTasks
  }

  protected[marathon] class WithTasksAndDeploymentsAndTaskFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure],
    app: V2AppDefinition)
      extends WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    def lastTaskFailure: Option[TaskFailure] = taskFailure
  }
}
