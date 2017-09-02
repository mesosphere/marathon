package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{ Actor, ActorRef, Cancellable, Props }
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.launchqueue.impl.RateLimiterActor._
import mesosphere.marathon.state.{ RunSpec, Timestamp }

import scala.concurrent.duration._

private[launchqueue] object RateLimiterActor {
  def props(
    rateLimiter: RateLimiter,
    launchQueueRef: ActorRef): Props =
    Props(new RateLimiterActor(
      rateLimiter, launchQueueRef
    ))

  case class DelayUpdate(runSpec: RunSpec, delayUntil: Timestamp)

  case class ResetDelay(runSpec: RunSpec)
  case class GetDelay(runSpec: RunSpec)
  private[impl] case class AddDelay(runSpec: RunSpec)
  private[impl] case class DecreaseDelay(runSpec: RunSpec)
  private[impl] case class AdvanceDelay(runSpec: RunSpec)

  private case object CleanupOverdueDelays
}

private class RateLimiterActor private (
    rateLimiter: RateLimiter,
    launchQueueRef: ActorRef) extends Actor with StrictLogging {
  var cleanup: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    val overdueDelayCleanupInterval = 10.seconds
    cleanup = context.system.scheduler.schedule(
      overdueDelayCleanupInterval, overdueDelayCleanupInterval, self, CleanupOverdueDelays)
    logger.info("started RateLimiterActor")
  }

  override def postStop(): Unit = {
    cleanup.cancel()
  }

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveCleanup,
      receiveDelayOps
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def receiveCleanup: Receive = {
    case CleanupOverdueDelays =>
      // If a run spec gets removed or updated, the delay should be reset.
      // In addition to that we remove overdue delays to ensure there are no leaks,
      // by calling this periodically.
      rateLimiter.cleanUpOverdueDelays()
  }

  private[this] def receiveDelayOps: Receive = {
    case GetDelay(runSpec) =>
      sender() ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))

    case AddDelay(runSpec) =>
      rateLimiter.addDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))

    case DecreaseDelay(_) => // ignore for now

    case AdvanceDelay(runSpec) =>
      rateLimiter.advanceDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))

    case ResetDelay(runSpec) =>
      rateLimiter.resetDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))
  }
}
