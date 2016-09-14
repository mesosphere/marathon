package mesosphere.marathon.core.instance

import java.util.Base64

import mesosphere.marathon.Protos
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ MarathonState, PathId, Timestamp }
import org.apache._
import org.apache.mesos.Protos.Attribute
// TODO PODs remove api import
import play.api.libs.json.{ Format, JsResult, JsString, JsValue, Json }

import scala.collection.immutable.Seq

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(instanceId: Instance.Id, agentInfo: Instance.AgentInfo, state: InstanceState, tasks: Seq[Task])
    extends MarathonState[Protos.Json, Instance] {

  def runSpecVersion: Timestamp = state.version
  def runSpecId: PathId = instanceId.runSpecId

  def isLaunched: Boolean = tasks.forall(task => task.launched.isDefined)

  override def mergeFromProto(message: Protos.Json): Instance = {
    Json.parse(message.getJson).as[Instance]
  }
  override def mergeFromProto(bytes: Array[Byte]): Instance = {
    mergeFromProto(Protos.Json.parseFrom(bytes))
  }
  override def toProto: Protos.Json = {
    Protos.Json.newBuilder().setJson(Json.stringify(Json.toJson(this))).build()
  }
  override def version: Timestamp = Timestamp.zero
}

object Instance {

  // TODO PODs remove api import
  import mesosphere.marathon.api.v2.json.Formats

  // required for legacy store, remove when legacy storage is removed.
  def apply(): Instance = {
    new Instance(Instance.Id(""), AgentInfo("", None, Nil),
      InstanceState(InstanceStatus.Unknown, Timestamp.zero, Timestamp.zero), Nil)
  }

  def instancesById(tasks: Iterable[Instance]): Map[Instance.Id, Instance] =
    tasks.iterator.map(task => task.instanceId -> task).toMap

  // TODO ju remove apply
  def apply(task: Task): Instance = new Instance(Id(task.taskId), task.agentInfo,
    InstanceState(task.status.taskStatus, task.status.startedAt.getOrElse(task.status.stagedAt),
      task.version.getOrElse(Timestamp.zero)), Seq(task))

  case class InstanceState(status: InstanceStatus, since: Timestamp, version: Timestamp)

  case class Id(idString: String) extends Ordered[Id] {
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    // TODO(jdef) move this somewhere else?
    lazy val mesosExecutorId: mesos.Protos.ExecutorID = mesos.Protos.ExecutorID.newBuilder().setValue(idString).build()

    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    private val InstanceIdRegex = """^(.+)[\._]([^_\.]+)$""".r

    def apply(executorId: mesos.Protos.ExecutorID): Id = new Id(executorId.getValue)

    def apply(taskId: Task.Id): Id = new Id(taskId.idString) // TODO PODs replace with proper calculation

    def runSpecId(instanceId: String): PathId = { // TODO PODs is this calculated correct?
      instanceId match {
        case InstanceIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
      }
    }

    def forRunSpec(id: PathId): Id = Task.Id.forRunSpec(id).instanceId
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Seq[mesos.Protos.Attribute])

  implicit class InstanceStatusComparison(val instance: Instance) extends AnyVal {
    def isReserved: Boolean = instance.state.status == InstanceStatus.Reserved
    def isCreated: Boolean = instance.state.status == InstanceStatus.Created
    def isError: Boolean = instance.state.status == InstanceStatus.Error
    def isFailed: Boolean = instance.state.status == InstanceStatus.Failed
    def isFinished: Boolean = instance.state.status == InstanceStatus.Finished
    def isKilled: Boolean = instance.state.status == InstanceStatus.Killed
    def isKilling: Boolean = instance.state.status == InstanceStatus.Killing
    def isRunning: Boolean = instance.state.status == InstanceStatus.Running
    def isStaging: Boolean = instance.state.status == InstanceStatus.Staging
    def isStarting: Boolean = instance.state.status == InstanceStatus.Starting
    def isUnreachable: Boolean = instance.state.status == InstanceStatus.Unreachable
    def isGone: Boolean = instance.state.status == InstanceStatus.Gone
    def isUnknown: Boolean = instance.state.status == InstanceStatus.Unknown
    def isDropped: Boolean = instance.state.status == InstanceStatus.Dropped
  }

  /**
    * Marathon has requested (or will request) that this instance be launched by Mesos.
    * @param instance is the thing that Marathon wants to launch
    * @param hostPorts is a list of actual (no dynamic!) hort-ports that are being requested from Mesos.
    */
  case class LaunchRequest(
    instance: Instance,
    hostPorts: Seq[Int])

  import Formats.TimestampFormat
  implicit object AttributeFormat extends Format[mesos.Protos.Attribute] {
    override def reads(json: JsValue): JsResult[Attribute] = {
      json.validate[String].map { base64 =>
        mesos.Protos.Attribute.parseFrom(Base64.getDecoder.decode(base64))
      }
    }

    override def writes(o: Attribute): JsValue = {
      JsString(Base64.getEncoder.encodeToString(o.toByteArray))
    }
  }
  implicit val agentFormat = Json.format[AgentInfo]
  implicit val idFormat = Json.format[Instance.Id]
  implicit val instanceStatusFormat = Json.format[InstanceStatus]
  implicit val instanceStateFormat = Json.format[InstanceState]
  implicit val instanceJsonFormat = Json.format[Instance]

  import mesosphere.mesos.Placed
  implicit class PlacedInstance(instance: Instance) extends Placed {
    override def hostname: String = instance.agentInfo.host
    override def attributes: Seq[Attribute] = instance.agentInfo.attributes
  }
}
