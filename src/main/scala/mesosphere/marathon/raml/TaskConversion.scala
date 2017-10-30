package mesosphere.marathon
package raml

import java.util.Base64

import core.task
import EnrichedTaskConversion._
import mesosphere.marathon.core.task.Task.Reservation.State.{ Garbage, Launched, New, Suspended, Unknown }

object TaskConversion extends DefaultConversions with OfferConversion {

  implicit val timeoutRamlWrites: Writes[task.Task.Reservation.Timeout, raml.Timeout] = Writes { timeout =>
    Timeout(
      initiated = timeout.initiated.toOffsetDateTime,
      deadline = timeout.deadline.toOffsetDateTime,
      reason = timeout.reason.toString
    )
  }

  implicit val stateRamlWrites: Writes[task.Task.Reservation.State, raml.State] = Writes { state =>

    val name = state match {
      case _: New => "new"
      case Launched => "launched"
      case _: Suspended => "suspended"
      case _: Garbage => "garbage"
      case _: Unknown => "unknown"
    }

    State(
      name = name,
      timeout = state.timeout.toRaml
    )
  }

  implicit val reservationRamlWrites: Writes[task.Task.Reservation, raml.Reservation] = Writes { res =>
    Reservation(
      volumeIds = res.volumeIds.map(_.toRaml),
      state = res.state.toRaml
    )
  }

  implicit val statusRamlWrites: Writes[task.Task.Status, raml.Status] = Writes { status =>
    Status(
      stagedAt = status.stagedAt.toOffsetDateTime,
      startedAt = status.startedAt.map(_.toOffsetDateTime),
      mesosStatus = status.mesosStatus.map(s => Base64.getEncoder.encodeToString(s.toByteArray)),
      condition = Condition.fromString(status.condition.toString)
        .getOrElse(throw new RuntimeException(s"can't convert mesos condition to RAML model: unexpected ${status.condition}"))
    )
  }

  implicit val taskRamlWrites: Writes[task.Task, raml.Task] = Writes { task =>
    Task(
      taskId = task.taskId.idString,
      reservation = task.reservationWithVolumes.toRaml,
      status = task.status.toRaml,
      runSpecVersion = task.runSpecVersion.toOffsetDateTime
    )
  }

}
