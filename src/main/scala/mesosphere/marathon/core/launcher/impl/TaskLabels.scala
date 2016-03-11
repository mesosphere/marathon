package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.task.Task
import org.apache.mesos.Protos.Label
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.mutable

object TaskLabels {
  private[this] final val TASK_ID_LABEL = "marathon_task_id"

  def taskIdForResource(resource: MesosProtos.Resource): Option[Task.Id] = {
    ResourceLabels(resource).labels.get(TASK_ID_LABEL).map(Task.Id(_))
  }

  def labelsForTask(task: Task): ResourceLabels = labelsForTask(task.taskId)
  def labelsForTask(taskId: Task.Id): ResourceLabels = ResourceLabels(Map(TASK_ID_LABEL -> taskId.idString))
}
