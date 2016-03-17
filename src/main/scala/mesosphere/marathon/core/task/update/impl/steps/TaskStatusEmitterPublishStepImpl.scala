package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import com.google.inject.Provider
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.update.TaskUpdateStep

import scala.concurrent.Future

/**
  * Forward the update to the taskStatusEmitter.
  */
class TaskStatusEmitterPublishStepImpl @Inject() (
    taskStatusEmitterProvider: Provider[TaskStatusEmitter]) extends TaskUpdateStep {

  private[this] lazy val taskStatusEmitter = taskStatusEmitterProvider.get()

  override def name: String = "emitUpdate"

  override def processUpdate(update: TaskUpdate): Future[_] = {
    taskStatusEmitter.publish(update)
    Future.successful(())
  }
}
