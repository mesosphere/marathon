package mesosphere.marathon.api.v1

import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.{ContainerInfo, Protos}
import mesosphere.marathon.state.{MarathonState, Timestamp, Timestamped}
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.PortIndices
import com.fasterxml.jackson.annotation.{
  JsonIgnore,
  JsonIgnoreProperties,
  JsonProperty
}
import org.apache.mesos.Protos.TaskState
import scala.collection.JavaConverters._
import java.lang.{Integer => JInt, Double => JDouble}
import mesosphere.mesos.protos.{Resource, ScalarResource}


/**
 * @author Tobi Knaup
 */

@PortIndices
@JsonIgnoreProperties(ignoreUnknown = true)
case class AppDefinition(

  @FieldNotEmpty
  @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$")
  id: String = "",

  cmd: String = "",

  env: Map[String, String] = Map.empty,

  @FieldMin(0)
  instances: JInt = AppDefinition.DEFAULT_INSTANCES,

  cpus: JDouble = AppDefinition.DEFAULT_CPUS,

  mem: JDouble = AppDefinition.DEFAULT_MEM,

  @FieldPattern(regexp="^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
  executor: String = "",

  constraints: Set[Constraint] = Set(),

  uris: Seq[String] = Seq(),

  @FieldPortsArray
  ports: Seq[JInt] = AppDefinition.DEFAULT_PORTS,

  /**
   * Number of new tasks this app may spawn per second in response to
   * terminated tasks. This prevents frequently failing apps from spamming
   * the cluster.
   */
  taskRateLimit: JDouble = AppDefinition.DEFAULT_TASK_RATE_LIMIT,

  container: Option[ContainerInfo] = None,

  healthChecks: Set[HealthCheck] = Set(),

  version: Timestamp = Timestamp.now

) extends MarathonState[Protos.ServiceDefinition, AppDefinition]
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

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setExecutor(executor)
      .setTaskRateLimit(taskRateLimit)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setVersion(version.toString)

    for (c <- container) builder.setContainer(c.toProto)

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
      taskRateLimit = proto.getTaskRateLimit,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala,
      constraints = proto.getConstraintsList.asScala.toSet,
      cpus = resourcesMap.get(Resource.CPUS).getOrElse(this.cpus),
      mem = resourcesMap.get(Resource.MEM).getOrElse(this.mem),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue),
      container = if (proto.hasContainer) Some(ContainerInfo(proto.getContainer))
                  else None,
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

  val RANDOM_PORT_VALUE = 0
  val DEFAULT_PORTS: Seq[JInt] = Seq(RANDOM_PORT_VALUE)

  val DEFAULT_INSTANCES = 0

  val DEFAULT_TASK_RATE_LIMIT = 1.0

  protected[marathon] class WithTaskCounts(
    taskTracker: TaskTracker,
    app: AppDefinition
  ) extends AppDefinition(
    app.id, app.cmd, app.env, app.instances, app.cpus, app.mem, app.executor,
    app.constraints, app.uris, app.ports, app.taskRateLimit, app.container,
    app.healthChecks, app.version
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
    app: AppDefinition
  ) extends WithTaskCounts(taskTracker, app) {
    @JsonProperty
    def tasks = appTasks
  }

}
