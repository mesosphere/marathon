package mesosphere.marathon.core.task.update.impl.steps

import java.time.OffsetDateTime

import akka.Done
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.storage.repository.ReadOnlyAppRepository
import mesosphere.marathon.core.task.{ Task, InstanceStateOp }
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue],
    appRepositoryProvider: Provider[ReadOnlyAppRepository]) extends TaskUpdateStep with InstanceChangeHandler {

  import NotifyRateLimiterStep._

  private[this] lazy val launchQueue = launchQueueProvider.get()
  private[this] lazy val appRepository = appRepositoryProvider.get()

  override def name: String = "notifyRateLimiter"

  // TODO(PODS): remove this function
  override def processUpdate(taskChanged: TaskChanged): Future[Done] = {
    // if MesosUpdate and status terminal != killed
    taskChanged.stateOp match {
      case InstanceStateOp.MesosUpdate(task, status: InstanceStatus.Terminal, mesosStatus, _) //
      if status != InstanceStatus.Killed =>
        notifyRateLimiter(mesosStatus, task)
      case _ => Future.successful(())
    }
  }

  override def process(update: InstanceChange): Future[Done] = {
    if (limitWorthy(update.status)) {
      notifyRateLimiter(update.runSpecId, update.instance.runSpecVersion.toOffsetDateTime)
    } else {
      Future.successful(Done)
    }
  }

  private[this] def notifyRateLimiter(runSpecId: PathId, version: OffsetDateTime): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    appRepository.getVersion(runSpecId, version).map { maybeApp =>
      // It would be nice if we could make sure that the delay gets send
      // to the AppTaskLauncherActor before we continue but that would require quite some work.
      //
      // In production, the worst case would be that we restart one or few tasks without delay –
      // this is unlikely but possible. It is unlikely that this causes noticeable harm.
      maybeApp.foreach(launchQueue.addDelay)
    }.map(_ => Done)
  }
}

private[steps] object NotifyRateLimiterStep {
  // A set of status that are worth rate limiting the associated runSpec
  val limitWorthy: Set[InstanceStatus] = Set(
    InstanceStatus.Dropped, InstanceStatus.Error, InstanceStatus.Failed, InstanceStatus.Gone
  )
}
