package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => MesosProtos }

object TaskLabels {
  private[this] final val FRAMEWORK_ID_LABEL = "marathon_framework_id"

  /**
    * For backwards compatibility reasons, this is still a field containing a taskId. Reservations and persistent
    * volumes from times before the introduction of instances are labeled with taskIds.
    * In case a resident instance is relaunched, Marathon will keep the instanceId but launch a task with a new taskId.
    * We can always derive the instanceId from the contained taskId.
    */
  private[this] final val TASK_ID_LABEL = "marathon_task_id"

  /**
    * Returns an instance id for which this reservation has been performed if the reservation was
    * labeled by this framework.
    */
  def instanceIdForResource(frameworkId: FrameworkId, resource: MesosProtos.Resource): Option[Instance.Id] = {
    val labels = ReservationLabels(resource)

    val maybeMatchingFrameworkId = labels.get(FRAMEWORK_ID_LABEL).filter(_ == frameworkId.id)
    def maybeInstanceId = labels.get(TASK_ID_LABEL).map(Task.Id(_).instanceId)

    maybeMatchingFrameworkId.flatMap(_ => maybeInstanceId)
  }

  def labelsForTask(frameworkId: FrameworkId, taskId: Task.Id): ReservationLabels = {
    ReservationLabels(Map(
      FRAMEWORK_ID_LABEL -> frameworkId.id,
      // This uses taskId.reservationId to match against the id that was originally used to create the reservation
      TASK_ID_LABEL -> taskId.reservationId
    ))
  }

  def labelKeysForReservations: Set[String] = Set(FRAMEWORK_ID_LABEL, TASK_ID_LABEL)

}
