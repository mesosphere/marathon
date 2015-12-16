package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusEmitter }
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Forward the update to the taskStatusEmitter.
  */
class TaskStatusEmitterPublishStepImpl @Inject() (taskStatusEmitter: TaskStatusEmitter) extends TaskStatusUpdateStep {
  override def name: String = "emitUpdate"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId
    taskStatusEmitter.publish(TaskStatusUpdate(timestamp, taskId, MarathonTaskStatus(status)))
    Future.successful(())
  }
}
