package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ AppRepository, PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueue: LaunchQueue,
    appRepository: AppRepository) extends TaskStatusUpdateStep {

  override def name: String = "notifyRateLimiter"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, task: MarathonTask, status: TaskStatus): Future[_] = {
    import org.apache.mesos.Protos.TaskState._

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_LOST =>
        notifyRateLimiter(appId, status, task)

      case _ =>
        Future.successful(())
    }
  }

  private[this] def notifyRateLimiter(
    appId: PathId,
    status: TaskStatus,
    task: MarathonTask): Future[_] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    appRepository.app(appId, Timestamp(task.getVersion)).map { maybeApp =>
      // It would be nice if we could make sure that the delay gets send
      // to the AppTaskLauncherActor before we continue but that would require quite some work.
      //
      // In production, the worst case would be that we restart one or few tasks without delay –
      // this is unlikely but possible. It is unlikely that this causes noticeable harm.
      maybeApp.foreach(launchQueue.addDelay)
    }
  }

}
