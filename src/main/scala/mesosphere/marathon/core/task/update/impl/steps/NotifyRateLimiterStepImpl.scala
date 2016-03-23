package mesosphere.marathon.core.task.update.impl.steps

import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.state.AppRepository
import org.apache.mesos.Protos
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue],
    appRepositoryProvider: Provider[AppRepository]) extends TaskUpdateStep {

  private[this] lazy val launchQueue = launchQueueProvider.get()
  private[this] lazy val appRepository = appRepositoryProvider.get()

  override def name: String = "notifyRateLimiter"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    // if MesosUpdate and status terminal != killed
    taskChanged.stateOp match {
      case TaskStateOp.MesosUpdate(task, TerminalNotKilled(status), _) =>
        notifyRateLimiter(status, task)
      case _ => Future.successful(())
    }
  }

  // it's only relevant for the rate limiter if we received a terminal Mesos Update that's not TASK_KILLED
  private[this] object TerminalNotKilled {
    def unapply(status: MarathonTaskStatus): Option[Protos.TaskStatus] = {
      status match {
        case terminal: MarathonTaskStatus.Terminal if !terminal.killed =>
          status.mesosStatus
        case _ => None
      }
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
