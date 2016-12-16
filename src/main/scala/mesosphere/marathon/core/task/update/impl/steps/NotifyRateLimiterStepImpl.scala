package mesosphere.marathon
package core.task.update.impl.steps

import java.time.OffsetDateTime

import akka.Done
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue],
    groupManagerProvider: Provider[GroupManager]) extends InstanceChangeHandler {

  import NotifyRateLimiterStep._

  private[this] lazy val launchQueue = launchQueueProvider.get()
  private[this] lazy val groupManager = groupManagerProvider.get()

  override def name: String = "notifyRateLimiter"

  override def process(update: InstanceChange): Future[Done] = {
    if (limitWorthy(update.condition)) {
      notifyRateLimiter(update.runSpecId, update.instance.runSpecVersion.toOffsetDateTime)
    } else {
      Future.successful(Done)
    }
  }

  private[this] def notifyRateLimiter(runSpecId: PathId, version: OffsetDateTime): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    groupManager.appVersion(runSpecId, version).map { maybeApp =>
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
  // A set of conditions that are worth rate limiting the associated runSpec
  val limitWorthy: Set[Condition] = Set(
    Condition.Dropped, Condition.Error, Condition.Failed, Condition.Gone, Condition.Finished
  )
}
