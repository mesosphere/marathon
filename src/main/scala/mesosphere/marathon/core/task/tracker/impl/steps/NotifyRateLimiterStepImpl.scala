package mesosphere.marathon.core.task.tracker.impl.steps

import com.google.inject.Inject
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.state.{ AppRepository, PathId, Timestamp }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueue: LaunchQueue,
    appRepository: AppRepository) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = "notifyRateLimiter"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], status: TaskStatus): Future[_] = {
    import org.apache.mesos.Protos.TaskState._

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        notifyRateLimiter(appId, status, maybeTask)

      case _ =>
        Future.successful(())
    }

  }

  private[this] def notifyRateLimiter(
    appId: PathId,
    status: TaskStatus,
    maybeTask: Option[MarathonTask]): Future[_] = {
    maybeTask match {
      case Some(task) if status.getState != TaskState.TASK_KILLED =>
        import scala.concurrent.ExecutionContext.Implicits.global
        appRepository.app(appId, Timestamp(task.getVersion)).map { maybeApp =>
          // It would be nice if we could make sure that the delay gets send
          // to the AppTaskLauncherActor before we continue but that would require quite some work.
          //
          // In production, it should not matter if we do not delay some task launches so much.
          maybeApp.foreach(launchQueue.addDelay)
        }
      case _ =>
        Future.successful(())
    }
  }

}
