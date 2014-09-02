package mesosphere.marathon.state

import java.lang.{ Double => JDouble, Integer => JInt }

import com.fasterxml.jackson.annotation.{ JsonIgnore, JsonIgnoreProperties, JsonProperty }
import mesosphere.marathon.Protos.{ Constraint, MarathonTask }
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidAppDefinition }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.Protos
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.TaskState

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
    "Port indices must address an element of this app's ports array."
  )

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array.
    */
  def portIndicesAreValid(): Boolean = {
    val validPortIndices = 0 until ports.size
    healthChecks.forall { hc =>
      validPortIndices contains hc.portIndex
    }
  }

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, Seq())
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

    container.foreach { c => builder.setContainer(c.toProto) }

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
        Some(proto.getCmd.getArgumentsList.asScala)
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
      ports = proto.getPortsList.asScala,
      requirePorts = proto.getRequirePorts,
      backoff = proto.getBackoff.milliseconds,
      backoffFactor = proto.getBackoffFactor,
      constraints = proto.getConstraintsList.asScala.toSet,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue),
      storeUrls = proto.getStoreUrlsList.asScala,
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      version = Timestamp(proto.getVersion),
      upgradeStrategy = if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy) else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.asScala.map(PathId.apply).toSet
    )
  }

  def hasDynamicPort = ports.contains(0)

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def withTaskCountsAndDeployments(
    service: MarathonSchedulerService,
    taskTracker: TaskTracker): AppDefinition.WithTaskCountsAndDeployments =
    new AppDefinition.WithTaskCountsAndDeployments(service, taskTracker, this)

  def withTasksAndDeployments(
    service: MarathonSchedulerService,
    taskTracker: TaskTracker): AppDefinition.WithTasksAndDeployments =
    new AppDefinition.WithTasksAndDeployments(service, taskTracker, this)

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
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    private val app: AppDefinition)
      extends AppDefinition(
        app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
        app.mem, app.disk, app.executor, app.constraints, app.uris,
        app.storeUrls, app.ports, app.requirePorts, app.backoff,
        app.backoffFactor, app.container, app.healthChecks, app.dependencies,
        app.upgradeStrategy, app.version) {

    /**
      * Snapshot of the known tasks for this app
      */
    @JsonIgnore
    protected[this] val appTasks: Seq[MarathonTask] =
      taskTracker.get(this.id).toSeq

    /**
      * Snapshot of the number of staged (but not running) tasks
      * for this app
      */
    @JsonProperty
    val tasksStaged: Int = appTasks.count { task =>
      task.getStagedAt != 0 && task.getStartedAt == 0
    }

    /**
      * Snapshot of the number of running tasks for this app
      */
    @JsonProperty
    val tasksRunning: Int = appTasks.count { task =>
      task.hasStatus && task.getStatus.getState == TaskState.TASK_RUNNING
    }

    import scala.concurrent.Await

    /**
      * Snapshot of the running deployments that affect this app
      */
    @JsonProperty
    def deployments: Seq[String] = {
      val deployments = Await.result(service.listRunningDeployments, 2.seconds)
      deployments.collect {
        case (d, _) if d.affectedApplicationIds contains app.id => d.id
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    private val app: AppDefinition)
      extends WithTaskCountsAndDeployments(service, taskTracker, app) {

    @JsonProperty
    def tasks = appTasks
  }

}
