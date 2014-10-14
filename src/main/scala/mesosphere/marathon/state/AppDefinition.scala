package mesosphere.marathon.state

import java.lang.{ Double => JDouble, Integer => JInt }

import com.fasterxml.jackson.annotation.{ JsonIgnoreProperties, JsonProperty }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidAppDefinition }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.Protos
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.apache.mesos.Protos.TaskState
import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
@ValidAppDefinition
case class AppDefinition(

  id: PathId = PathId.empty,

  cmd: Option[String] = None,

  args: Option[Seq[String]] = None,

  user: Option[String] = None,

  env: Map[String, String] = Map.empty,

  @FieldMin(0) instances: JInt = AppDefinition.DEFAULT_INSTANCES,

  cpus: JDouble = AppDefinition.DEFAULT_CPUS,

  mem: JDouble = AppDefinition.DEFAULT_MEM,

  disk: JDouble = AppDefinition.DEFAULT_DISK,

  @FieldPattern(regexp = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$") executor: String = "",

  constraints: Set[Constraint] = Set.empty,

  uris: Seq[String] = Seq.empty,

  storeUrls: Seq[String] = Seq.empty,

  @FieldPortsArray ports: Seq[JInt] = AppDefinition.DEFAULT_PORTS,

  requirePorts: Boolean = AppDefinition.DEFAULT_REQUIRE_PORTS,

  @FieldJsonProperty("backoffSeconds") backoff: FiniteDuration = AppDefinition.DEFAULT_BACKOFF,

  backoffFactor: JDouble = AppDefinition.DEFAULT_BACKOFF_FACTOR,

  container: Option[Container] = None,

  healthChecks: Set[HealthCheck] = Set.empty,

  dependencies: Set[PathId] = Set.empty,

  upgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty,

  version: Timestamp = Timestamp.now()) extends MarathonState[Protos.ServiceDefinition, AppDefinition]
    with Timestamped {

  import mesosphere.mesos.protos.Implicits._

  assert(
    portIndicesAreValid(),
    "Health check port indices must address an element of the ports array or container port mappings."
  )

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean = {
    val validPortIndices = 0 until hostPorts.size
    healthChecks.forall { hc =>
      validPortIndices contains hc.portIndex
    }
  }

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, None, Seq.empty)
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id.toString)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setRequirePorts(requirePorts)
      .setBackoff(backoff.toMillis)
      .setBackoffFactor(backoffFactor)
      .setExecutor(executor)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setVersion(version.toString)
      .setUpgradeStrategy(upgradeStrategy.toProto)
      .addAllDependencies(dependencies.map(_.toString).asJava)
      .addAllStoreUrls(storeUrls.asJava)

    container.foreach { c => builder.setContainer(c.toProto()) }

    builder.build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, String] =
      proto.getCmd.getEnvironment.getVariablesList.asScala.map {
        v => v.getName -> v.getValue
      }.toMap

    val resourcesMap: Map[String, JDouble] =
      proto.getResourcesList.asScala.map {
        r => r.getName -> (r.getScalar.getValue: JDouble)
      }.toMap

    val commandOption =
      if (proto.getCmd.hasValue && proto.getCmd.getValue.nonEmpty)
        Some(proto.getCmd.getValue)
      else None

    val argsOption =
      if (commandOption.isEmpty)
        Some(proto.getCmd.getArgumentsList.asScala.to[Seq])
      else None

    val containerOption =
      if (proto.hasContainer)
        Some(Container(proto.getContainer))
      else if (proto.getCmd.hasContainer)
        Some(Container(proto.getCmd.getContainer))
      else if (proto.hasOBSOLETEContainer)
        Some(Container(proto.getOBSOLETEContainer))
      else None

    AppDefinition(
      id = proto.getId.toPath,
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala.to[Seq],
      requirePorts = proto.getRequirePorts,
      backoff = proto.getBackoff.milliseconds,
      backoffFactor = proto.getBackoffFactor,
      constraints = proto.getConstraintsList.asScala.toSet,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue).to[Seq],
      storeUrls = proto.getStoreUrlsList.asScala.to[Seq],
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      version = Timestamp(proto.getVersion),
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.asScala.map(PathId.apply).toSet
    )
  }

  def portMappings(): Option[Seq[PortMapping]] =
    for {
      c <- container
      d <- c.docker
      n <- d.network if n == Network.BRIDGE
      pms <- d.portMappings
    } yield pms

  def containerHostPorts(): Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.hostPort.toInt)

  def containerServicePorts(): Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort.toInt)

  def hostPorts(): Seq[Int] =
    containerHostPorts.getOrElse(ports.map(_.toInt))

  def servicePorts(): Seq[Int] =
    containerServicePorts.getOrElse(ports.map(_.toInt))

  def hasDynamicPort(): Boolean = servicePorts.contains(0)

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def withTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTaskCountsAndDeployments = {
    new AppDefinition.WithTaskCountsAndDeployments(appTasks, runningDeployments, this)
  }

  def withTasksAndDeployments(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTasksAndDeployments =
    new AppDefinition.WithTasksAndDeployments(appTasks, runningDeployments, this)

  def withTasksAndDeploymentsAndFailures(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure]): AppDefinition.WithTasksAndDeploymentsAndTaskFailures =
    new AppDefinition.WithTasksAndDeploymentsAndTaskFailures(appTasks, runningDeployments, taskFailure, this)

  def isOnlyScaleChange(to: AppDefinition): Boolean =
    !isUpgrade(to) && (instances != to.instances)

  def isUpgrade(to: AppDefinition): Boolean = {
    cmd != to.cmd ||
      env != to.env ||
      cpus != to.cpus ||
      mem != to.mem ||
      disk != to.disk ||
      uris.toSet != to.uris.toSet ||
      constraints != to.constraints ||
      container != to.container ||
      ports.toSet != to.ports.toSet ||
      requirePorts != to.requirePorts ||
      executor != to.executor ||
      healthChecks != to.healthChecks ||
      backoff != to.backoff ||
      backoffFactor != to.backoffFactor ||
      dependencies != to.dependencies ||
      upgradeStrategy != to.upgradeStrategy ||
      storeUrls.toSet != to.storeUrls.toSet ||
      user != to.user ||
      backoff != to.backoff ||
      backoffFactor != to.backoffFactor
  }
}

object AppDefinition {
  val DEFAULT_CPUS = 1.0

  val DEFAULT_MEM = 128.0

  val DEFAULT_DISK = 0.0

  val RANDOM_PORT_VALUE = 0

  val DEFAULT_PORTS: Seq[JInt] = Seq(RANDOM_PORT_VALUE)

  val DEFAULT_REQUIRE_PORTS = false

  val DEFAULT_INSTANCES = 1

  val DEFAULT_BACKOFF = 1.second

  val DEFAULT_BACKOFF_FACTOR = 1.15

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  protected[marathon] class WithTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends AppDefinition(
        app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
        app.mem, app.disk, app.executor, app.constraints, app.uris,
        app.storeUrls, app.ports, app.requirePorts, app.backoff,
        app.backoffFactor, app.container, app.healthChecks, app.dependencies,
        app.upgradeStrategy, app.version) {

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
        eTask.task.getStatus.getState == TaskState.TASK_RUNNING
    }

    /**
      * Snapshot of the running deployments that affect this app
      */
    @JsonProperty
    def deployments: Seq[Identifiable] = {
      runningDeployments.collect {
        case plan if plan.affectedApplicationIds contains app.id => Identifiable(plan.id)
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends WithTaskCountsAndDeployments(appTasks, runningDeployments, app) {

    @JsonProperty
    def tasks: Seq[EnrichedTask] = appTasks
  }

  protected[marathon] class WithTasksAndDeploymentsAndTaskFailures(
    appTasks: Seq[EnrichedTask],
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure],
    private val app: AppDefinition)
      extends WithTasksAndDeployments(appTasks, runningDeployments, app) {

    @JsonProperty
    def lastTaskFailure: Option[TaskFailure] = taskFailure
  }

}
