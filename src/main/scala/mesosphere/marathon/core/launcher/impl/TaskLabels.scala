package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.task.Task
import org.apache.mesos.{ Protos => MesosProtos }

object TaskLabels {
  private[this] final val TASK_ID_LABEL = "marathon_task_id"

  def labelsForTask(task: Task): Map[String, String] = labelsForTask(task.taskId)
  def labelsForTask(taskId: Task.Id): Map[String, String] = Map(TASK_ID_LABEL -> taskId.idString)

  def mesosLabelsForTask(taskId: Task.Id): MesosProtos.Labels = {
    mesosLabels(labelsForTask(taskId))
  }

  private[this] def mesosLabels(labels: Map[String, String]): MesosProtos.Labels = {
    val labelsBuilder = MesosProtos.Labels.newBuilder()
    labels.foreach {
      case (k, v) =>
        labelsBuilder.addLabels(MesosProtos.Label.newBuilder().setKey(k).setValue(v))
    }
    labelsBuilder.build()
  }
}
