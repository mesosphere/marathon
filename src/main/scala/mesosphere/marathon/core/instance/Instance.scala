package mesosphere.marathon.core.instance

import mesosphere.marathon.core.task.Task
import org.apache._

trait Instance {
  def id: Instance.Id
  def agentInfo: Instance.AgentInfo
  def status: Task.Status

  // TODO ju needed??
  def launched: Option[Task.Launched]

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

  trait Id extends Ordered[Id]

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Iterable[mesos.Protos.Attribute])

}
