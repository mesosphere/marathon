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
import scala.async.Async._

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (
    launchQueueProvider: Provider[LaunchQueue],
    groupManagerProvider: Provider[GroupManager]) extends InstanceChangeHandler {

  import NotifyRateLimiterStep._
  import scala.concurrent.ExecutionContext.Implicits.global

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

  @SuppressWarnings(Array("all")) // async/await
  private[this] def notifyRateLimiter(runSpecId: PathId, version: OffsetDateTime): Future[Done] = async {
    val appFuture = groupManager.appVersion(runSpecId, version)
    val podFuture = groupManager.podVersion(runSpecId, version)
    val (app, pod) = (await(appFuture), await(podFuture))
    app.foreach(launchQueue.addDelay)
    pod.foreach(launchQueue.addDelay)
    Done
  }
}

private[steps] object NotifyRateLimiterStep {
  // A set of conditions that are worth rate limiting the associated runSpec
  val limitWorthy: Set[Condition] = Set(
    Condition.Dropped, Condition.Error, Condition.Failed, Condition.Gone, Condition.Finished
  )
}
