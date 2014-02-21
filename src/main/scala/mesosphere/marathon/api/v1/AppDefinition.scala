package mesosphere.marathon.api.v1

import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.{ContainerInfo, Protos}
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.FieldConstraints.{
  FieldPattern,
  FieldNotEmpty,
  FieldJsonDeserialize
}
import com.fasterxml.jackson.annotation.{
  JsonInclude,
  JsonIgnore,
  JsonIgnoreProperties,
  JsonProperty
}
import com.fasterxml.jackson.annotation.JsonInclude.Include
import org.apache.mesos.Protos.TaskState
import scala.collection.JavaConverters._


/**
 * @author Tobi Knaup
 */
@JsonIgnoreProperties(ignoreUnknown = true)
case class AppDefinition(

  @FieldNotEmpty
  @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$")
  id: String = "",

  cmd: String = "",

  env: Map[String, String] = Map.empty,

  instances: Int = AppDefinition.DEFAULT_INSTANCES,

  cpus: Double = AppDefinition.DEFAULT_CPUS,

  mem: Double = AppDefinition.DEFAULT_MEM,

  @FieldPattern(regexp="(^//cmd$)|(^/[^/].*$)|")
  executor: String = "",

  constraints: Set[Constraint] = Set(),

  uris: Seq[String] = Seq(),

  ports: Seq[Int] = Seq(0),

  // Number of new tasks this app may spawn per second in response to
  // terminated tasks. This prevents frequently failing apps from spamming
  // the cluster.
  taskRateLimit: Double = AppDefinition.DEFAULT_TASK_RATE_LIMIT,

  @FieldJsonDeserialize(contentAs = classOf[ContainerInfo])
  container: Option[ContainerInfo] = None

) extends MarathonState[Protos.ServiceDefinition, AppDefinition] {

  // the default constructor exists solely for interop with automatic
  // (de)serializers
  def this() = this(id = "")

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, Seq())
    val cpusResource = TaskBuilder.scalarResource(AppDefinition.CPUS, cpus)
    val memResource = TaskBuilder.scalarResource(AppDefinition.MEM, mem)

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.map(_.asInstanceOf[Integer]).asJava)
      .setExecutor(executor)
      .setTaskRateLimit(taskRateLimit)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)

    for (c <- container) builder.setContainer(c.toProto)

    builder.build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, String] =
      proto.getCmd.getEnvironment.getVariablesList.asScala.map {
        v => v.getName -> v.getValue
      }.toMap

    val resourcesMap: Map[String, Double] =
      proto.getResourcesList.asScala.map {
        r => r.getName -> r.getScalar.getValue
      }.toMap

    AppDefinition(
      id = proto.getId,
      cmd = proto.getCmd.getValue,
      executor = proto.getExecutor,
      taskRateLimit = proto.getTaskRateLimit,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala.asInstanceOf[Seq[Int]],
      constraints = proto.getConstraintsList.asScala.toSet,
      container = if (proto.hasContainer) Some(ContainerInfo(proto.getContainer))
                  else None,
      cpus = resourcesMap.get(AppDefinition.CPUS).getOrElse(this.cpus),
      mem = resourcesMap.get(AppDefinition.MEM).getOrElse(this.mem),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue)
    )
  }

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def withTaskCounts(taskTracker: TaskTracker): AppDefinition.WithTaskCounts =
    new AppDefinition.WithTaskCounts(
      taskTracker, id, cmd, env, instances, cpus, mem, executor,
      constraints, uris, ports, taskRateLimit, container
    )

  def withTasks(taskTracker: TaskTracker): AppDefinition.WithTasks =
    new AppDefinition.WithTasks(
      taskTracker, id, cmd, env, instances, cpus, mem, executor,
      constraints, uris, ports, taskRateLimit, container
    )

}

object AppDefinition {
  val CPUS = "cpus"
  val DEFAULT_CPUS = 1.0

  val MEM = "mem"
  val DEFAULT_MEM = 128.0

  val DEFAULT_INSTANCES = 0

  val DEFAULT_TASK_RATE_LIMIT = 1.0

  import mesosphere.marathon.tasks.TaskTracker

  protected[marathon] class WithTaskCounts(
    taskTracker: TaskTracker,
    id: String,
    cmd: String,
    env: Map[String, String],
    instances: Int,
    cpus: Double,
    mem: Double,
    executor: String,
    constraints: Set[Constraint],
    uris: Seq[String],
    ports: Seq[Int],
    taskRateLimit: Double,
    container: Option[ContainerInfo]
  ) extends AppDefinition(
    id, cmd, env, instances, cpus, mem, executor,
    constraints, uris, ports, taskRateLimit, container
  ) {
    @JsonIgnore
    protected def appTasks(): Seq[MarathonTask] =
      taskTracker.get(this.id).toSeq

    @JsonProperty
    def tasksStaged(): Int = appTasks.count { task =>
      task.getStagedAt != 0 && task.getStartedAt == 0
    }

    @JsonProperty
    def tasksRunning(): Int = appTasks.count { task =>
      val statusList = task.getStatusesList.asScala
      statusList.nonEmpty && statusList.last.getState == TaskState.TASK_RUNNING
    }
  }

  protected[marathon] class WithTasks(
        taskTracker: TaskTracker,
    id: String,
    cmd: String,
    env: Map[String, String],
    instances: Int,
    cpus: Double,
    mem: Double,
    executor: String,
    constraints: Set[Constraint],
    uris: Seq[String],
    ports: Seq[Int],
    taskRateLimit: Double,
    container: Option[ContainerInfo]
  ) extends WithTaskCounts(
    taskTracker, id, cmd, env, instances, cpus, mem, executor,
    constraints, uris, ports, taskRateLimit, container
  ) {
    @JsonProperty
    @JsonInclude
    def tasks = appTasks
  }

}
