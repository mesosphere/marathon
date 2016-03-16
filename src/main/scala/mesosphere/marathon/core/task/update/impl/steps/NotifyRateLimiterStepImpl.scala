package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.{ Provider, Inject }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ AppRepository, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue],
    appRepositoryProvider: Provider[AppRepository]) extends TaskStatusUpdateStep {

  private[this] lazy val launchQueue = launchQueueProvider.get()
  private[this] lazy val appRepository = appRepositoryProvider.get()

  override def name: String = "notifyRateLimiter"

  override def processUpdate(timestamp: Timestamp, task: Task, status: TaskStatus): Future[_] = {
    import org.apache.mesos.Protos.TaskState._

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_LOST =>
        notifyRateLimiter(status, task)

      case _ =>
        Future.successful(())
    }
  }

  private[this] def notifyRateLimiter(status: TaskStatus, task: Task): Future[_] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    task.launched.fold(Future.successful(())) { launched =>
      appRepository.app(task.appId, launched.appVersion).map { maybeApp =>
        // It would be nice if we could make sure that the delay gets send
        // to the AppTaskLauncherActor before we continue but that would require quite some work.
        //
        // In production, the worst case would be that we restart one or few tasks without delay –
        // this is unlikely but possible. It is unlikely that this causes noticeable harm.
        maybeApp.foreach(launchQueue.addDelay)
      }
    }
  }
}
