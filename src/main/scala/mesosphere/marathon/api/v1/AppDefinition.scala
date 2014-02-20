package mesosphere.marathon.api.v1

import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.{ContainerInfo, Protos}
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import javax.validation.constraints.Pattern
import org.hibernate.validator.constraints.NotEmpty
import org.apache.mesos.Protos.TaskState
import com.fasterxml.jackson.annotation.{
  JsonInclude,
  JsonIgnoreProperties,
  JsonProperty
}
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import scala.collection.JavaConverters._
import scala.annotation.target.field

object FieldConstraints {
  type FieldNotEmpty = NotEmpty @field
  type FieldPattern = Pattern @field
  type FieldJsonInclude = JsonInclude @field
  type FieldJsonDeserialize = JsonDeserialize @field
}

import FieldConstraints._

/**
 * @author Tobi Knaup
 */
@JsonIgnoreProperties(ignoreUnknown = true)
case class AppDefinition(

  @FieldNotEmpty
  @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$")
  var id: String = "",

  var cmd: String = "",

  var env: Map[String, String] = Map.empty,

  var instances: Int = AppDefinition.DEFAULT_INSTANCES,

  var cpus: Double = AppDefinition.DEFAULT_CPUS,

  var mem: Double = AppDefinition.DEFAULT_MEM,

  @FieldPattern(regexp="(^//cmd$)|(^/[^/].*$)|")
  var executor: String = "",

  var constraints: Set[Constraint] = Set(),

  var uris: Seq[String] = Seq(),

  var ports: Seq[Int] = Seq(0),

  // Number of new tasks this app may spawn per second in response to
  // terminated tasks. This prevents frequently failing apps from spamming
  // the cluster.
  var taskRateLimit: Double = AppDefinition.DEFAULT_TASK_RATE_LIMIT,

  @FieldJsonInclude(Include.NON_EMPTY)
  var tasks: Seq[MarathonTask] = Seq(),

  @FieldJsonDeserialize(contentAs = classOf[ContainerInfo])
  var container: Option[ContainerInfo] = None

) extends MarathonState[Protos.ServiceDefinition, AppDefinition] {

  // the default constructor exists solely for interop with automatic
  // (de)serializers
  def this() = this(id = "")

  @JsonProperty()
  def tasksStaged(): Int = tasks.count { task =>
    task.getStagedAt != 0 &&
    task.getStartedAt == 0
  }

  @JsonProperty()
  def tasksRunning(): Int = tasks.count { task =>
    val statusList = task.getStatusesList.asScala
    statusList.nonEmpty && statusList.last.getState == TaskState.TASK_RUNNING
  }

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
}

object AppDefinition {
  val CPUS = "cpus"
  val DEFAULT_CPUS = 1.0

  val MEM = "mem"
  val DEFAULT_MEM = 128.0

  val DEFAULT_INSTANCES = 0

  val DEFAULT_TASK_RATE_LIMIT = 1.0
}
