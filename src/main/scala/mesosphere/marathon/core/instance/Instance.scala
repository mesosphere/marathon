package mesosphere.marathon.core.instance

import com.fasterxml.uuid.{EthernetAddress, Generators}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache._

trait Instance {
  def id: Instance.Id
  def agentInfo: Instance.AgentInfo
  def state: InstanceState

  def isLaunched: Boolean

  def isReserved: Boolean
  def isCreated: Boolean
  def isError: Boolean
  def isFailed: Boolean
  def isFinished: Boolean
  def isKilled: Boolean
  def isKilling: Boolean
  def isRunning: Boolean
  def isStaging: Boolean
  def isStarting: Boolean
  def isUnreachable: Boolean
  def isGone: Boolean
  def isUnknown: Boolean
  def isDropped: Boolean
}

object Instance {
  case class InstanceState(status: InstanceStatus, since: Timestamp)

  case class Id(idString: String) extends Ordered[Id] {
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    lazy val mesosTaskId: mesos.Protos.TaskID = mesos.Protos.TaskID.newBuilder().setValue(idString).build()

    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    private val runSpecDelimiter = "."
    private val TaskIdRegex = """^(.+)[\._]([^_\.]+)$""".r
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def runSpecId(taskId: String): PathId = {
      taskId match {
        case TaskIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
        case _ => throw new MatchError(s"taskId $taskId is no valid identifier")
      }
    }

    def apply(mesosTaskId: mesos.Protos.TaskID): Id = new Id(mesosTaskId.getValue)

    def forRunSpec(id: PathId): Instance.Id = {
      val taskId = id.safePath + runSpecDelimiter + uuidGenerator.generate()
      Instance.Id(taskId)
    }
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Iterable[mesos.Protos.Attribute])

}
