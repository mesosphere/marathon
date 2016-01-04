package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Notify the launch queue of this update.
  */
class NotifyLaunchQueueStepImpl @Inject() (launchQueue: LaunchQueue) extends TaskStatusUpdateStep {
  override def name: String = "notifyLaunchQueue"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId
    val update = TaskStatusUpdate(timestamp, taskId, MarathonTaskStatus(status))
    launchQueue.notifyOfTaskUpdate(update)
  }
}
