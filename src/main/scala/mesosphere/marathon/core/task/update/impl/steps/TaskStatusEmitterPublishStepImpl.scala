package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import com.google.inject.Provider
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep

import scala.concurrent.Future

/**
  * Forward the update to the taskStatusEmitter.
  */
class TaskStatusEmitterPublishStepImpl @Inject() (
    taskStatusEmitterProvider: Provider[TaskStatusEmitter]) extends TaskUpdateStep {

  private[this] lazy val taskStatusEmitter = taskStatusEmitterProvider.get()

  override def name: String = "emitUpdate"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    taskStatusEmitter.publish(taskChanged)
    Future.successful(())
  }
}
