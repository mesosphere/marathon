package mesosphere.marathon.state

import java.lang.{ Double => JDouble, Integer => JInt }

import com.fasterxml.jackson.annotation.{ JsonIgnoreProperties, JsonProperty }
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.{ PortIndices, ValidAppDefinition }
import mesosphere.marathon.health.{ HealthCheck, HealthCounts }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.apache.mesos.{ Protos => mesos }
import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
@ValidAppDefinition
case class AppDefinition(

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
      hc.protocol == Protocol.COMMAND || (validPortIndices contains hc.portIndex)
    }
  }

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, None, None, Seq.empty)
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)
    val appLabels = labels.map {
      case (key, value) =>
        mesos.Parameter.newBuilder
          .setKey(key)
          .setValue(value)
          .build
    }

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id.toString)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setRequirePorts(requirePorts)
      .setBackoff(backoff.toMillis)
      .setBackoffFactor(backoffFactor)
      .setMaxLaunchDelay(maxLaunchDelay.toMillis)
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
      .addAllLabels(appLabels.asJava)

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
      maxLaunchDelay = proto.getMaxLaunchDelay.milliseconds,
      constraints = proto.getConstraintsList.asScala.toSet,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue).to[Seq],
      storeUrls = proto.getStoreUrlsList.asScala.to[Seq],
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      labels = proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap,
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
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTaskCountsAndDeployments = {
    new AppDefinition.WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, this)
  }

  def withTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan]): AppDefinition.WithTasksAndDeployments =
    new AppDefinition.WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, this)

  def withTasksAndDeploymentsAndFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure]): AppDefinition.WithTasksAndDeploymentsAndTaskFailures =
    new AppDefinition.WithTasksAndDeploymentsAndTaskFailures(
      appTasks, healthCounts,
      runningDeployments, taskFailure, this
    )

  def withNormalizedVersion: AppDefinition = copy(version = Timestamp(0))

  def isOnlyScaleChange(to: AppDefinition): Boolean =
    !isUpgrade(to) && (instances != to.instances)

  def isUpgrade(to: AppDefinition): Boolean =
    this != to.copy(instances = instances, version = version)
}

object AppDefinition {

  val RandomPortValue: Int = 0

  // App defaults
  val DefaultId: PathId = PathId.empty

  val DefaultCmd: Option[String] = None

  val DefaultArgs: Option[Seq[String]] = None

  val DefaultUser: Option[String] = None

  val DefaultEnv: Map[String, String] = Map.empty

  val DefaultInstances: Int = 1

  val DefaultCpus: Double = 1.0

  val DefaultMem: Double = 128.0

  val DefaultDisk: Double = 0.0

  val DefaultExecutor: String = ""

  val DefaultConstraints: Set[Constraint] = Set.empty

  val DefaultUris: Seq[String] = Seq.empty

  val DefaultStoreUrls: Seq[String] = Seq.empty

  val DefaultPorts: Seq[JInt] = Seq(RandomPortValue)

  val DefaultRequirePorts: Boolean = false

  val DefaultBackoff: FiniteDuration = 1.second

  val DefaultBackoffFactor = 1.15

  val DefaultMaxLaunchDelay: FiniteDuration = 1.hour

  val DefaultContainer: Option[Container] = None

  val DefaultHealthChecks: Set[HealthCheck] = Set.empty

  val DefaultDependencies: Set[PathId] = Set.empty

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  val DefaultLabels: Map[String, String] = Map.empty

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  protected[marathon] class WithTaskCountsAndDeployments(
    appTasks: Seq[EnrichedTask],
    healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends AppDefinition(
        app.id, app.cmd, app.args, app.user, app.env, app.instances, app.cpus,
        app.mem, app.disk, app.executor, app.constraints, app.uris,
        app.storeUrls, app.ports, app.requirePorts, app.backoff,
        app.backoffFactor, app.maxLaunchDelay, app.container,
        app.healthChecks, app.dependencies, app.upgradeStrategy,
        app.labels, app.version) {

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
        case plan if plan.affectedApplicationIds contains app.id => Identifiable(plan.id)
      }
    }
  }

  protected[marathon] class WithTasksAndDeployments(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    private val app: AppDefinition)
      extends WithTaskCountsAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def tasks: Seq[EnrichedTask] = appTasks
  }

  protected[marathon] class WithTasksAndDeploymentsAndTaskFailures(
    appTasks: Seq[EnrichedTask], healthCounts: HealthCounts,
    runningDeployments: Seq[DeploymentPlan],
    taskFailure: Option[TaskFailure],
    private val app: AppDefinition)
      extends WithTasksAndDeployments(appTasks, healthCounts, runningDeployments, app) {

    @JsonProperty
    def lastTaskFailure: Option[TaskFailure] = taskFailure
  }

}
