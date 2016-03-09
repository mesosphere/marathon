package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import com.google.inject.Provider
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.update.TaskUpdateStep

import scala.concurrent.Future

/**
  * Notify the launch queue of this update.
  */
class NotifyLaunchQueueStepImpl @Inject() (launchQueueProvider: Provider[LaunchQueue]) extends TaskUpdateStep {
  override def name: String = "notifyLaunchQueue"

  private[this] lazy val launchQueue = launchQueueProvider.get()

  override def processUpdate(update: TaskUpdate): Future[_] = {
    launchQueue.notifyOfTaskUpdate(update)
  }
}
