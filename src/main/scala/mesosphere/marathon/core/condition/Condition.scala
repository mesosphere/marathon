package mesosphere.marathon
package core.condition

import play.api.libs.json._
import org.apache.mesos.Protos.{TaskState => MesosTaskState}
import scala.collection.breakOut

/**
  * To define the status of an Instance, this trait is used and stored for each Task in Task.Status.
  * The existing case objects are:
  * - marathon exclusive status
  * - representations of the mesos.Protos.TaskStatus
  * - mapping of existing (soon-to-be deprecated) mesos.Protos.TaskStatus.TASK_LOST to the new representations
  */
sealed trait Condition extends Product with Serializable {
  /**
    * @return whether condition is considered a lost state.
    *
    * UnreachableInactive is not considered Lost because it depends on the context
    */
  def isLost: Boolean = {
    import Condition._
    this match {
      case Gone | Unreachable | Unknown | Dropped => true
      case _ => false
    }
  }

  /**
    * @return whether condition is a terminal state.
    */
  def isTerminal: Boolean = this match {
    case _: Condition.Terminal => true
    case _ => false
  }

  /**
    * @return whether considered is considered active.
    */
  def isActive: Boolean = this match {
    case _: Condition.Active => true
    case _ => false
  }
}

object Condition {

  sealed trait Terminal extends Condition
  sealed trait Failure extends Terminal
  sealed trait Active extends Condition

  /** Scheduled: Task should be launched by matching offers. Mesos does not know anything about it. */
  case object Scheduled extends Condition

  /** Provisioned: An offer for task has been accepted but Mesos did not start the task yet. */
  case object Provisioned extends Active

  /** Reserved: Task with persistent volume has reservation, but is not launched or scheduled to be launched */
  case object Reserved extends Condition

  /** Error: indicates that a task launch attempt failed because of an error in the task specification */
  case object Error extends Failure

  /** Failed: task aborted with an error */
  case object Failed extends Failure

  /** Finished: task completes successfully */
  case object Finished extends Terminal

  /** Killed: task was killed */
  case object Killed extends Terminal

  /** Killing: the request to kill the task has been received, but the task has not yet been killed */
  case object Killing extends Active

  /** Running: the state after the task has begun running successfully */
  case object Running extends Active

  /**
    * Staging: the master has received the framework’s request to launch the task but the task has not yet started to
    * run
    */
  case object Staging extends Active

  /** Starting: task is currently starting */
  case object Starting extends Active

  /** Unreachable: the master has not heard from the agent running the task for a configurable period of time */
  case object Unreachable extends Active

  /**
    * The task has been unreachable for a configurable time. A replacement task is started but this one won't be killed
    * yet.
    */
  case object UnreachableInactive extends Condition

  /** Gone: the task was running on an agent that has been terminated */
  case object Gone extends Failure

  /** Dropped: the task failed to launch because of a transient error (e.g., spontaneously disconnected agent) */
  case object Dropped extends Failure

  /** Unknown: the master has no knowledge of the task */
  case object Unknown extends Failure

  private[this] val conditionToMesosTaskState = {
    Map(
      Error -> MesosTaskState.TASK_ERROR,
      Failed -> MesosTaskState.TASK_FAILED,
      Finished -> MesosTaskState.TASK_FINISHED,
      Killed -> MesosTaskState.TASK_KILLED,
      Killing -> MesosTaskState.TASK_KILLING,
      Running -> MesosTaskState.TASK_RUNNING,
      Staging -> MesosTaskState.TASK_STAGING,
      Starting -> MesosTaskState.TASK_STARTING,
      Unreachable -> MesosTaskState.TASK_UNREACHABLE,
      UnreachableInactive -> MesosTaskState.TASK_UNREACHABLE,
      Gone -> MesosTaskState.TASK_GONE,
      Dropped -> MesosTaskState.TASK_DROPPED,
      Unknown -> MesosTaskState.TASK_UNKNOWN)
  }

  val all = Seq(Reserved, Error, Failed, Finished, Killed, Killing, Running, Staging, Starting, Unreachable,
    UnreachableInactive, Gone, Dropped, Unknown, Scheduled, Provisioned)

  private val lowerCaseStringToCondition: Map[String, Condition] = all.map { c =>
    c.toString.toLowerCase -> c
  }(breakOut)

  /** Converts the Condition to a mesos task state where such a conversion is possible */
  def toMesosTaskState(condition: Condition): Option[MesosTaskState] =
    conditionToMesosTaskState.get(condition)

  /**
    * Converts the Condition to a mesos task state where such a conversion is possible; if not possible, return
    * TASK_STAGING.
    */
  def toMesosTaskStateOrStaging(condition: Condition): MesosTaskState =
    conditionToMesosTaskState.getOrElse(condition, MesosTaskState.TASK_STAGING)

  def apply(str: String): Condition =
    lowerCaseStringToCondition.getOrElse(str.toLowerCase, Unknown)

  def unapply(condition: Condition): Option[String] = Some(condition.toString.toLowerCase)

  val conditionReader = new Reads[Condition] {
    private def readString(j: JsReadable) = j.validate[String].map(Condition(_))
    override def reads(json: JsValue): JsResult[Condition] =
      readString(json).orElse {
        json.validate[JsObject].flatMap { obj => readString(obj \ "str") }
      }
  }

  implicit val conditionFormat = Format[Condition](
    conditionReader,
    Writes(condition => JsString(condition.toString)))
}
