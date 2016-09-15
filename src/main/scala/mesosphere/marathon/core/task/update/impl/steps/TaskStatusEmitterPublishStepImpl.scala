package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import akka.Done
import com.google.inject.Provider
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.task.bus.TaskStatusEmitter

import scala.concurrent.Future

/**
  * Forward the update to the taskStatusEmitter.
  */
// TODO(PODS): remove the emitter stuff and taskStatusObservables
// the taskStatusObservables are only used in OfferMatcherLaunchTokensActor, which could subscribe
// to the eventStream, instead
// then this whole step and related code could be removed.
class TaskStatusEmitterPublishStepImpl @Inject() (
    taskStatusEmitterProvider: Provider[TaskStatusEmitter]) extends InstanceChangeHandler {

  private[this] lazy val taskStatusEmitter = taskStatusEmitterProvider.get()

  override def name: String = "emitUpdate"

  override def process(update: InstanceChange): Future[Done] = {
    taskStatusEmitter.publish(update)
    Future.successful(Done)
  }
}
