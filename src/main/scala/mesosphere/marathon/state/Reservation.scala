package mesosphere.marathon
package state

import mesosphere.marathon.core.instance.{LocalVolumeId, Instance => CoreInstance, Reservation => CoreReservation}
import mesosphere.marathon.core.task.Task
import play.api.libs.json._

/**
  * Storage model for [[CoreReservation]] with an optional id since older Marathon versions did not
  * persist reservation ids.
  */
case class Reservation(volumeIds: Seq[LocalVolumeId], state: CoreReservation.State, id: Option[CoreReservation.Id]) {

  /**
    * @return This stored reservation as a core reservation.
    */
  def toCoreReservation(tasksMap: Map[Task.Id, Task], instanceId: CoreInstance.Id): CoreReservation = {
    CoreReservation(volumeIds, state, id.getOrElse(Reservation.inferReservationId(tasksMap, instanceId)))
  }
}

object Reservation {

  /**
    * @return The core reservation in storage model.
    */
  def fromCoreReservation(coreReservation: CoreReservation): Reservation =
    Reservation(coreReservation.volumeIds, coreReservation.state, Some(coreReservation.id))

  def appTask(tasksMap: Map[Task.Id, Task]): Option[Task] = tasksMap.headOption.map(_._2)

  def inferReservationId(tasksMap: Map[Task.Id, Task], instanceId: CoreInstance.Id): CoreReservation.Id = {
    if (tasksMap.nonEmpty) {
      val taskId = appTask(tasksMap).getOrElse(throw new IllegalStateException(s"No task in $instanceId")).taskId
      taskId match {
        case Task.LegacyId(runSpecId, separator: String, uuid) =>
          CoreReservation.LegacyId(runSpecId, separator, uuid)
        case Task.LegacyResidentId(runSpecId, separator, uuid, _) =>
          CoreReservation.LegacyId(runSpecId, separator, uuid)
        case Task.EphemeralTaskId(instanceId, _) =>
          CoreReservation.SimplifiedId(instanceId)
        case Task.TaskIdWithIncarnation(instanceId, _, _) =>
          CoreReservation.SimplifiedId(instanceId)
      }
    } else {
      CoreReservation.SimplifiedId(instanceId)
    }
  }

  implicit lazy val reservationIdFormat: Format[CoreReservation.Id] = Format(
    Reads.of[String].map(CoreReservation.Id(_)),
    Writes[CoreReservation.Id] { id => JsString(id.label) }
  )
  implicit val reservationFormat: OFormat[Reservation] = Json.format[Reservation]
}
