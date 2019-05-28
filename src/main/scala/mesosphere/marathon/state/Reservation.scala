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
    // Infer the reservation id in case it was not persisted yet.
    val reservationId = id.getOrElse(Reservation.inferReservationId(tasksMap, instanceId))
    CoreReservation(volumeIds, state, reservationId)
  }
}

object Reservation {

  /**
    * @return The core reservation in storage model.
    */
  def fromCoreReservation(coreReservation: CoreReservation): Reservation =
    Reservation(coreReservation.volumeIds, coreReservation.state, Some(coreReservation.id))

  def appTask(tasksMap: Map[Task.Id, Task]): Option[Task] = tasksMap.headOption.map(_._2)

  /**
    * Infer the reservation id for an instance.
    *
    * In older Marathon versions the reservation id was the run spec path and the uuid of the instance
    * joined by a separator. Eg ephemeral tasks and persistent apps used it. This is expressed by
    * [[mesosphere.marathon.core.instance.Reservation.LegacyId]].
    *
    * Later Marathon versions used the instance id, ie `<run spec path>.marathon-<uuid>`, as the
    * reservation id. This is expressed by [[mesosphere.marathon.core.instance.Reservation.SimplifiedId]].
    *
    * The reservation id was in all cases determined by the `appTask.taskId`. The app task is just the
    * first task of the tasks map of an instance.
    *
    * This method is only used if the saved reservation does not include an id. This will be true for
    * all instance from apps and pods started with Marathon 1.8.194-1590825ea and earlier. All apps
    * and pods from later version will have a reservation id persisted.
    *
    * Future Marathon versions that only allow upgrades from Marathon 1.9 and later can drop the
    * inferences and should safely assumed that all reservation have a persisted id.
    *
    * @param tasksMap All tasks of an instance.
    * @param instanceId The id of the instance this reservation belongs to.
    * @return
    */
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
