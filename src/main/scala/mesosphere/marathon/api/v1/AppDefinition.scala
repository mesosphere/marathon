package mesosphere.marathon.api.v1

import mesosphere.marathon.{ ContainerInfo, Protos }
import mesosphere.marathon.state.{ Migration, MarathonState, Timestamp, Timestamped }
import mesosphere.marathon.Protos.{ StorageVersion, MarathonTask, Constraint }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.PortIndices
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import com.fasterxml.jackson.annotation.{
  JsonIgnore,
  JsonIgnoreProperties,
  JsonProperty
}
import org.apache.mesos.Protos.TaskState
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import java.lang.{ Integer => JInt, Double => JDouble }

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
case class AppDefinition(

  @FieldNotEmpty @FieldPattern(regexp = "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])$") id: String = "",

  cmd: String = "",

  env: Map[String, String] = Map.empty,

  @FieldMin(0) instances: JInt = AppDefinition.DEFAULT_INSTANCES,

  cpus: JDouble = AppDefinition.DEFAULT_CPUS,

  mem: JDouble = AppDefinition.DEFAULT_MEM,

  disk: JDouble = AppDefinition.DEFAULT_DISK,

  @FieldPattern(regexp = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$") executor: String = "",

  constraints: Set[Constraint] = Set(),

  uris: Seq[String] = Seq(),

  @FieldPortsArray ports: Seq[JInt] = AppDefinition.DEFAULT_PORTS,

  @FieldJsonProperty("launchDelaySeconds") launchDelay: FiniteDuration = AppDefinition.DEFAULT_LAUNCH_DELAY,

  launchDelayFactor: JDouble = AppDefinition.DEFAULT_LAUNCH_FACTOR,

  container: Option[ContainerInfo] = None,

  healthChecks: Set[HealthCheck] = Set(),

  version: Timestamp = Timestamp.now) extends MarathonState[Protos.ServiceDefinition, AppDefinition]
    with Timestamped {

  import mesosphere.mesos.protos.Implicits._

  assert(
    portIndicesAreValid,
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
      .setId(id)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setLaunchDelay(launchDelay.toMillis)
      .setLaunchDelayFactor(launchDelayFactor)
      .setExecutor(executor)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setVersion(version.toString)

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

    AppDefinition(
      id = proto.getId,
      cmd = proto.getCmd.getValue,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala,
      launchDelay = proto.getLaunchDelay.milliseconds,
      launchDelayFactor = proto.getLaunchDelayFactor,
      constraints = proto.getConstraintsList.asScala.toSet,
      cpus = resourcesMap.get(Resource.CPUS).getOrElse(this.cpus),
      mem = resourcesMap.get(Resource.MEM).getOrElse(this.mem),
      disk = resourcesMap.get(Resource.DISK).getOrElse(this.disk),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue),
      container = if (proto.getCmd.hasContainer) {
        Some(ContainerInfo(proto.getCmd.getContainer))
      }
      else if (proto.hasOBSOLETEContainer) {
        val oldContainer = proto.getOBSOLETEContainer
        Some(ContainerInfo(oldContainer.getImage.toStringUtf8, oldContainer.getOptionsList.asScala.toSeq.map(_.toStringUtf8)))
      }
      else {
        None
      },
      healthChecks =
        proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      version = Timestamp(proto.getVersion)
    )
  }

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def withTaskCounts(taskTracker: TaskTracker): AppDefinition.WithTaskCounts =
    new AppDefinition.WithTaskCounts(taskTracker, this)

  def withTasks(taskTracker: TaskTracker): AppDefinition.WithTasks =
    new AppDefinition.WithTasks(taskTracker, this)

}

object AppDefinition {
  val DEFAULT_CPUS = 1.0

  val DEFAULT_MEM = 128.0

  val DEFAULT_DISK = 0.0

  val RANDOM_PORT_VALUE = 0

  val DEFAULT_PORTS: Seq[JInt] = Seq(RANDOM_PORT_VALUE)

  val DEFAULT_INSTANCES = 0

  val DEFAULT_LAUNCH_DELAY = 1.second

  val DEFAULT_LAUNCH_FACTOR = 1.15

  protected[marathon] class WithTaskCounts(
    taskTracker: TaskTracker,
    app: AppDefinition) extends AppDefinition(
    app.id, app.cmd, app.env, app.instances, app.cpus, app.mem, app.disk,
    app.executor, app.constraints, app.uris, app.ports, app.launchDelay,
    app.launchDelayFactor, app.container, app.healthChecks, app.version
  ) {

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
      val statusList = task.getStatusesList.asScala
      statusList.nonEmpty && statusList.last.getState == TaskState.TASK_RUNNING
    }
  }

  protected[marathon] class WithTasks(
      taskTracker: TaskTracker,
      app: AppDefinition) extends WithTaskCounts(taskTracker, app) {
    @JsonProperty
    def tasks = appTasks
  }

  implicit object AppDefinitionMigration extends Migration[AppDefinition] {
    override def needsMigration(version: StorageVersion): Boolean = {
      if (version.getMajor == 0 && version.getMinor < 6) {
        true
      }
      // add other migration cases
      else {
        false
      }
    }

    override def migrate(version: StorageVersion, obj: AppDefinition): AppDefinition = {
      if (version.getMajor == 0 && version.getMinor < 6) {
        // container changes are handled in the AppDefinition object
        obj.copy(id = obj.id.toLowerCase().replaceAll("_", "-"))
      }
      // add other migration cases
      else {
        obj
      }
    }
  }
}
