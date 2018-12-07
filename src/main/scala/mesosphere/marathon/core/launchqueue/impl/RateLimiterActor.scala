package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.launchqueue.impl.RateLimiterActor._
import mesosphere.marathon.core.leadership.LeaderDeferrable
import mesosphere.marathon.state.{RunSpec, RunSpecConfigRef}

import scala.concurrent.duration._

private[launchqueue] object RateLimiterActor {
  def props(
    rateLimiter: RateLimiter): Props =
    Props(new RateLimiterActor(rateLimiter))

  private[impl] case class AddDelay(runSpec: RunSpec)
  private[impl] case class DecreaseDelay(runSpec: RunSpec)
  private[impl] case class AdvanceDelay(runSpec: RunSpec)
  private[impl] case class ResetDelay(runSpec: RunSpec)
  private[impl] case class GetDelay(ref: RunSpecConfigRef)
  @LeaderDeferrable private[launchqueue] case object Subscribe
  private[launchqueue] case object Unsubscribe

  private case object CleanupOverdueDelays
}

private class RateLimiterActor private (rateLimiter: RateLimiter) extends Actor with StrictLogging {
  var cleanup: Cancellable = _
  var subscribers: Set[ActorRef] = Set.empty

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
      rateLimiter.cleanUpOverdueDelays().foreach { configRef =>
        notify(RateLimiter.DelayUpdate(configRef, None))
      }
  }

  private[this] def receiveDelayOps: Receive = {
    case Subscribe =>
      if (!subscribers.contains(sender)) {
        subscribers += sender
        rateLimiter.currentDelays.foreach { delay: RateLimiter.DelayUpdate =>
          sender ! delay
        }
      }

    case Unsubscribe =>
      subscribers -= sender

    case GetDelay(ref) =>
      sender() ! RateLimiter.DelayUpdate(ref, rateLimiter.getDeadline(ref))

    case AddDelay(runSpec) =>
      rateLimiter.addDelay(runSpec)
      notify(RateLimiter.DelayUpdate(runSpec.configRef, rateLimiter.getDeadline(runSpec.configRef)))

    case DecreaseDelay(_) => // ignore for now

    case AdvanceDelay(runSpec) =>
      rateLimiter.advanceDelay(runSpec)
      notify(RateLimiter.DelayUpdate(runSpec.configRef, rateLimiter.getDeadline(runSpec.configRef)))

    case ResetDelay(runSpec) =>
      rateLimiter.resetDelay(runSpec)
      notify(RateLimiter.DelayUpdate(runSpec.configRef, None))
  }

  private def notify(update: RateLimiter.DelayUpdate): Unit = {
    // Have launchQueue subscribe the same way?
    subscribers.foreach { _ ! update }
  }
}
