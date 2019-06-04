package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.instance.Reservation
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{Protos => MesosProtos}

object TaskLabels {
  private[this] final val FRAMEWORK_ID_LABEL = "marathon_framework_id"

  /**
    * For backwards compatibility reasons, this is still a field containing a taskId. Reservations and persistent
    * volumes from times before the introduction of instances are labeled with taskIds.
    * In case a resident instance is relaunched, Marathon will keep the instanceId but launch a task with a new taskId.
    * We can always derive the instanceId from the contained taskId.
    */
  private[this] final val TASK_ID_LABEL = "marathon_task_id"

  def reservationFromResource(resource: MesosProtos.Resource): Option[Reservation.Id] = {
    val labels = ReservationLabels(resource)
    labels.get(TASK_ID_LABEL).map(Reservation.Id(_))
  }

  def labelsForTask(frameworkId: FrameworkId, reservationId: Reservation.Id): ReservationLabels = {
    ReservationLabels(Map(
      FRAMEWORK_ID_LABEL -> frameworkId.id,
      // This uses taskId.reservationId to match against the id that was originally used to create the reservation
      // We probably want to call it RESERVATION_ID_LABEL in the future. See MARATHON-8517.
      TASK_ID_LABEL -> reservationId.label
    ))
  }

  def labelKeysForReservations: Set[String] = Set(FRAMEWORK_ID_LABEL, TASK_ID_LABEL)
}
