package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusEmitter }
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Forward the update to the taskStatusEmitter.
  */
class TaskStatusEmitterPublishStepImpl @Inject() (taskStatusEmitter: TaskStatusEmitter) extends TaskStatusUpdateStep {
  override def name: String = "emitUpdate"

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    taskStatusEmitter.publish(TaskStatusUpdate(timestamp, task.taskId, MarathonTaskStatus(status)))
    Future.successful(())
  }
}
