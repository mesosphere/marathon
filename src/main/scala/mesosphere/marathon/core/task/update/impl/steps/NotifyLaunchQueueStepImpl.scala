package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import com.google.inject.Provider
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Notify the launch queue of this update.
  */
class NotifyLaunchQueueStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue]) extends TaskStatusUpdateStep {
  override def name: String = "notifyLaunchQueue"

  private[this] lazy val launchQueue = launchQueueProvider.get()

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    val taskId = Task.Id(status.getTaskId)
    val update = TaskStatusUpdate(timestamp, taskId, MarathonTaskStatus(status))
    launchQueue.notifyOfTaskUpdate(update)
  }
}
