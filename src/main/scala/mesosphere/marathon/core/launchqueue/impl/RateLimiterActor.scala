package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.LoggingReceive
import mesosphere.marathon.core.launchqueue.impl.RateLimiterActor.{
  AddDelay,
  CleanupOverdueDelays,
  DecreaseDelay,
  DelayUpdate,
  GetDelay,
  ResetDelay,
  ResetDelayResponse
}
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
  case object ResetDelayResponse

  case class GetDelay(runSpec: RunSpec)
  private[impl] case class AddDelay(runSpec: RunSpec)
  private[impl] case class DecreaseDelay(runSpec: RunSpec)

  private case object CleanupOverdueDelays
}

private class RateLimiterActor private (
    rateLimiter: RateLimiter,
    launchQueueRef: ActorRef) extends Actor with ActorLogging {
  var cleanup: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    cleanup = context.system.scheduler.schedule(10.seconds, 10.seconds, self, CleanupOverdueDelays)
    log.info("started RateLimiterActor")
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
      // If an run spec gets removed or updated, the delay should be reset.
      // Still, we can remove overdue delays before that and also make leaks less likely
      // by calling this periodically.
      rateLimiter.cleanUpOverdueDelays()
  }

  private[this] def receiveDelayOps: Receive = {
    case GetDelay(runSpec) =>
      sender() ! DelayUpdate(runSpec, rateLimiter.getDelay(runSpec))

    case AddDelay(runSpec) =>
      rateLimiter.addDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDelay(runSpec))

    case DecreaseDelay(runSpec) => // ignore for now

    case ResetDelay(runSpec) =>
      rateLimiter.resetDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDelay(runSpec))
      sender() ! ResetDelayResponse
  }
}
