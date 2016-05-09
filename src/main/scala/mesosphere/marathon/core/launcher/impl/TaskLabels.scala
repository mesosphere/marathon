package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.task.Task
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => MesosProtos }

object TaskLabels {
  private[this] final val FRAMEWORK_ID_LABEL = "marathon_framework_id"
  private[this] final val TASK_ID_LABEL = "marathon_task_id"

  /**
    * Returns a the task id for which this reservation has been performed if the reservation was
    * labeled by this framework.
    */
  def taskIdForResource(frameworkId: FrameworkId, resource: MesosProtos.Resource): Option[Task.Id] = {
    val labels = ReservationLabels(resource)

    val maybeMatchingFrameworkId = labels.get(FRAMEWORK_ID_LABEL).filter(_ == frameworkId.id)
    def maybeTaskId = labels.get(TASK_ID_LABEL).map(Task.Id(_))

    maybeMatchingFrameworkId.flatMap(_ => maybeTaskId)
  }

  def labelsForTask(frameworkId: FrameworkId, task: Task): ReservationLabels =
    labelsForTask(frameworkId, task.taskId)

  def labelsForTask(frameworkId: FrameworkId, taskId: Task.Id): ReservationLabels =
    ReservationLabels(Map(
      FRAMEWORK_ID_LABEL -> frameworkId.id,
      TASK_ID_LABEL -> taskId.idString
    ))

  def labelKeysForTaskReservations: Set[String] = Set(FRAMEWORK_ID_LABEL, TASK_ID_LABEL)

}
