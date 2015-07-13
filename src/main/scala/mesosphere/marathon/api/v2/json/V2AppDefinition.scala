package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble, Integer => JInt }

import com.fasterxml.jackson.annotation.{ JsonIgnoreProperties, JsonProperty }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidV2AppDefinition }
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
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

    @FieldJsonProperty("backoffSeconds") backoff: FiniteDuration = AppDefinition.DefaultBackoff,

    backoffFactor: JDouble = AppDefinition.DefaultBackoffFactor,

    @FieldJsonProperty("maxLaunchDelaySeconds") maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

    container: Option[Container] = AppDefinition.DefaultContainer,

    healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

    dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

    upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

    labels: Map[String, String] = AppDefinition.DefaultLabels,

    acceptedResourceRoles: Option[Set[String]] = None,

    version: Timestamp = Timestamp.now()) extends Timestamped {

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
  def toAppDefinition: AppDefinition =
    AppDefinition(
      id, cmd, args, user, env, instances, cpus,
      mem, disk, executor, constraints, uris,
      storeUrls, ports, requirePorts, backoff,
      backoffFactor, maxLaunchDelay, container,
      healthChecks, dependencies, upgradeStrategy,
      labels, acceptedResourceRoles, version)

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

  def apply(app: AppDefinition): V2AppDefinition =
    V2AppDefinition(
      app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
      app.mem, app.disk, app.executor, app.constraints, app.uris,
      app.storeUrls, app.ports, app.requirePorts, app.backoff,
      app.backoffFactor, app.maxLaunchDelay, app.container,
      app.healthChecks, app.dependencies, app.upgradeStrategy,
      app.labels, app.acceptedResourceRoles, app.version)

  protected[marathon] class WithTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask],
    healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: V2AppDefinition)
      extends V2AppDefinition(
        app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
        app.mem, app.disk, app.executor, app.constraints, app.uris,
        app.storeUrls, app.ports, app.requirePorts, app.backoff,
        app.backoffFactor, app.maxLaunchDelay, app.container,
        app.healthChecks, app.dependencies, app.upgradeStrategy,
        app.labels, app.acceptedResourceRoles, app.version) {

    /**
      * Snapshot of the number of staged (but not running) tasks
      * for this app
      */
    @JsonProperty
    val tasksStaged: Int = appTasks.count { eTask =>
      eTask.task.getStagedAt != 0 && eTask.task.getStartedAt == 0
    }

    /**
      * Snapshot of the number of running tasks for this app
      */
    @JsonProperty
    val tasksRunning: Int = appTasks.count { eTask =>
      eTask.task.hasStatus &&
        eTask.task.getStatus.getState == mesos.TaskState.TASK_RUNNING
    }

    /**
      * Snapshot of the number of healthy tasks for this app
      */
    @JsonProperty
    val tasksHealthy: Int = healthCounts.healthy

    /**
      * Snapshot of the number of unhealthy tasks for this app
      */
    @JsonProperty
    val tasksUnhealthy: Int = healthCounts.unhealthy

    /**
      * Snapshot of the running deployments that affect this app
      */
    @JsonProperty
    def deployments: Seq[Identifiable] = {
      runningDeployments.collect {
        case plan: DeploymentPlan if plan.affectedApplicationIds contains app.id => Identifiable(plan.id)
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: V2AppDefinition)
      extends WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def tasks: Seq[EnrichedTask] = appTasks
  }

  protected[marathon] class WithTasksAndDeploymentsAndTaskFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure],
    private val app: V2AppDefinition)
      extends WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def lastTaskFailure: Option[TaskFailure] = taskFailure
  }
}
