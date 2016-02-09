package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskUpdater
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Update task tracker corresponding to the event.
  */
class UpdateTaskTrackerStepImpl @Inject() (taskUpdater: TaskUpdater) extends TaskStatusUpdateStep {

  def name: String = "updateTaskTracker"

  def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    taskUpdater.statusUpdate(task.taskId.appId, status)
  }
}
