package mesosphere.marathon
package core.instance

import java.util.UUID

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.instance.Instance.Prefix
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{PathId, Timestamp}
import play.api.libs.json._

/**
  * Represents a reservation for all resources that are needed for launching an instance
  * and associated persistent local volumes.
  */
case class Reservation(volumeIds: Seq[LocalVolumeId], state: Reservation.State, id: Reservation.Id)

object Reservation {

  /**
    * Base identifier of a reservation.
    */
  sealed trait Id {
    val label: String
    val instanceId: Instance.Id
  }

  /**
    * The old reservation id that uses a separator.
    *
    * @param runSpecId The run spec the reservation belongs to.
    * @param separator The separator of run spec id and uuid.
    * @param uuid The unique id of the reservation. It is the same id of the instance.
    */
  case class LegacyId(runSpecId: PathId, separator: String, uuid: UUID) extends Id {
    override lazy val label: String = runSpecId.safePath + separator + uuid

    /**
      * See [[mesosphere.marathon.core.task.Task.LegacyResidentId.instanceId]] and [[mesosphere.marathon.core.task.Task.LegacyId.instanceId]]
      */
    override lazy val instanceId: Instance.Id = Instance.Id(runSpecId, Instance.PrefixMarathon, uuid)
  }

  /**
    * A simplified reservation id that just uses the instance id.
    *
    * @param instanceId The identifier for the instance this reservation belongs to.
    */
  case class SimplifiedId(val instanceId: Instance.Id) extends Id {
    val label: String = instanceId.idString
  }

  object Id {

    private val SimplifiedIdRegex = """^(.+)\.(instance-|marathon-)([^\.]+)$""".r
    private val LegacyIdRegex = """^(.+)([\._])([^_\.]+)$""".r
    /**
      * Parse reservation id from task label.
      *
      * @param label The raw task label that encodes the reservation id.
      * @return LegacyId for old task or SimplifiedId for new tasks.
      * @throws MatchError
      */
    def apply(label: String): Id = label match {
      case SimplifiedIdRegex(safeRunSpecId, prefix, uuid) =>
        val runSpec = PathId.fromSafePath(safeRunSpecId)
        val instanceId = Instance.Id(runSpec, Prefix.fromString(prefix), UUID.fromString(uuid))
        SimplifiedId(instanceId)
      case LegacyIdRegex(safeRunSpecId, separator, uuid) =>
        val runSpecId = PathId.fromSafePath(safeRunSpecId)
        LegacyId(runSpecId, separator, UUID.fromString(uuid))
      case _ => throw new MatchError(s"reservation id $label does not include a valid instance identifier")
    }

  }

  /**
    * A timeout that eventually leads to a state transition
    *
    * @param initiated When this timeout was setup
    * @param deadline When this timeout should become effective
    * @param reason The reason why this timeout was set up
    */
  case class Timeout(initiated: Timestamp, deadline: Timestamp, reason: Timeout.Reason)

  object Timeout {
    sealed trait Reason
    object Reason {
      /** A timeout because the instance could not be relaunched */
      case object RelaunchEscalationTimeout extends Reason
      /** A timeout because we got no ack for reserved resources or persistent volumes */
      case object ReservationTimeout extends Reason
    }

    implicit object ReasonFormat extends Format[Timeout.Reason] {
      override def reads(json: JsValue): JsResult[Timeout.Reason] = {
        json.validate[String].map {
          case "RelaunchEscalationTimeout" => Reason.RelaunchEscalationTimeout
          case "ReservationTimeout" => Reason.ReservationTimeout
        }
      }

      override def writes(o: Timeout.Reason): JsValue = {
        JsString(o.toString)
      }
    }

    implicit val timeoutFormat: Format[Timeout] = Json.format[Timeout]
  }

  sealed trait State extends Product with Serializable {
    /** Defines when this state should time out and for which reason */
    def timeout: Option[Timeout]
  }

  object State {
    /** A newly reserved resident instance */
    case class New(timeout: Option[Timeout]) extends State
    /** A launched resident instance, never has a timeout */
    case object Launched extends State {
      override def timeout: Option[Timeout] = None
    }
    /** A resident instance that has been running before but terminated and can be relaunched */
    case class Suspended(timeout: Option[Timeout]) extends State
    /** A resident instance whose reservation and persistent volumes are being destroyed */
    case class Garbage(timeout: Option[Timeout]) extends State
    /** An unknown resident instance created because of unknown reservations/persistent volumes */
    case class Unknown(timeout: Option[Timeout]) extends State

    implicit object StateFormat extends Format[State] {
      override def reads(json: JsValue): JsResult[State] = {
        implicit val timeoutFormat: Format[Timeout] = Timeout.timeoutFormat
        (json \ "timeout").validateOpt[Timeout].flatMap { timeout =>
          (json \ "name").validate[String].map {
            case "new" => New(timeout)
            case "launched" => Launched
            case "suspended" => Suspended(timeout)
            case "garbage" => Garbage(timeout)
            case _ => Unknown(timeout)
          }
        }
      }

      override def writes(o: State): JsValue = {
        val timeout = Json.toJson(o.timeout)
        o match {
          case _: New => JsObject(Seq("name" -> JsString("new"), "timeout" -> timeout))
          case Launched => JsObject(Seq("name" -> JsString("launched"), "timeout" -> timeout))
          case _: Suspended => JsObject(Seq("name" -> JsString("suspended"), "timeout" -> timeout))
          case _: Garbage => JsObject(Seq("name" -> JsString("garbage"), "timeout" -> timeout))
          case _: Unknown => JsObject(Seq("name" -> JsString("unknown"), "timeout" -> timeout))
        }
      }
    }
  }

  implicit lazy val reservationIdFormat: Format[Reservation.Id] = Format(
    Reads.of[String].map(Reservation.Id(_)),
    Writes[Reservation.Id] { id => JsString(id.label) }
  )
  implicit val reservationFormat: OFormat[Reservation] = Json.format[Reservation]

  /**
    * Infer the reservation id for an instance.
    *
    * In older Marathon versions the reservation id was the run spec path and the uuid of the instance
    * joined by a separator. Eg ephemeral tasks and persistent apps used it. This is expressed by
    * [[mesosphere.marathon.core.instance.Reservation.LegacyId]].
    *
    * Later Marathon versions used the instance id, ie `<run spec path>.marathon-<uuid>`, as the
    * reservation id. This is expressed by [[mesosphere.marathon.core.instance.Reservation.SimplifiedId]].
    * Notice the extra "marathon-" in the id string.
    *
    * The reservation id was in all cases determined by the `appTask.taskId`. The app task is just the
    * first task of the tasks map of an instance.
    *
    * This method is only used if the saved reservation does not include an id. This will be true for
    * all instance from apps and pods started with Marathon 1.8.194-1590825ea and earlier. All apps
    * and pods from later version will have a reservation id persisted.
    *
    * Future Marathon versions that only allow upgrades from Marathon 1.9 and later can drop the
    * inference and should safely assume that all reservation have a persisted id.
    *
    * @param tasksMap All tasks of an instance.
    * @param instanceId The id of the instance this reservation belongs to.
    * @return The proper reservation id.
    */
  def inferReservationId(tasksMap: Map[Task.Id, Task], instanceId: Instance.Id): Reservation.Id = {
    if (tasksMap.nonEmpty) {
      val taskId = appTask(tasksMap).getOrElse(throw new IllegalStateException(s"No task in $instanceId")).taskId
      taskId match {
        case Task.LegacyId(runSpecId, separator: String, uuid) => LegacyId(runSpecId, separator, uuid)
        case Task.LegacyResidentId(runSpecId, separator, uuid, _) => LegacyId(runSpecId, separator, uuid)
        case Task.EphemeralTaskId(instanceId, _) => SimplifiedId(instanceId)
        case Task.TaskIdWithIncarnation(instanceId, _, _) => SimplifiedId(instanceId)
      }
    } else {
      SimplifiedId(instanceId)
    }
  }

  def appTask(tasksMap: Map[Task.Id, Task]): Option[Task] = tasksMap.headOption.map(_._2)
}
