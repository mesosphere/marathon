package mesosphere.marathon
package core.task.update.impl.steps

import java.time.OffsetDateTime

import akka.Done
import com.google.inject.{Inject, Provider}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{PathId, RunSpec}

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
  override def metricName: String = "notify-rate-limiter"

  override def process(update: InstanceChange): Future[Done] = {
    update.condition match {
      // RateLimiter is triggered for every InstanceChange event. Right now, InstanceChange is very tight to condition
      // change so InstanceChange pretty much was condition change all the time. But now, we will be having
      // InstanceChange event also for goal changes and we don't want to trigger RateLimiter for that
      case condition if limitWorthy(condition) && update.stateUpdated =>
        notifyRateLimiter(update.runSpecId, update.instance.runSpecVersion.toOffsetDateTime, launchQueue.addDelay)
      case condition if advanceWorthy(condition) && update.stateUpdated =>
        notifyRateLimiter(update.runSpecId, update.instance.runSpecVersion.toOffsetDateTime, launchQueue.advanceDelay)
      case _ =>
        Future.successful(Done)
    }
  }

  private[this] def notifyRateLimiter(runSpecId: PathId, version: OffsetDateTime, fn: RunSpec => Unit): Future[Done] =
    async {
      val appFuture = groupManager.appVersion(runSpecId, version)
      val podFuture = groupManager.podVersion(runSpecId, version)
      val (app, pod) = (await(appFuture), await(podFuture))
      app.foreach(fn)
      pod.foreach(fn)
      Done
    }
}

private[steps] object NotifyRateLimiterStep {
  // A set of conditions that are worth rate limiting the associated runSpec
  val limitWorthy: Set[Condition] = Set(
    Condition.Dropped, Condition.Error, Condition.Failed, Condition.Gone, Condition.Finished
  )

  // A set of conditions that are worth advancing an existing delay of the corresponding runSpec
  val advanceWorthy: Set[Condition] = Set(
    Condition.Staging, Condition.Starting, Condition.Running, Condition.Provisioned
  )
}
