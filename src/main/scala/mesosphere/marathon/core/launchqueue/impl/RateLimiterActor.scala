package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{ Actor, ActorRef, Cancellable, Props }
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.launchqueue.impl.RateLimiterActor.{ AddDelay, DecreaseDelay, DelayUpdate, GetDelay, ResetDelay, ResetDelayResponse, ResetViableTasksDelays }
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

  private case object ResetViableTasksDelays
}

private class RateLimiterActor private (
    rateLimiter: RateLimiter,
    launchQueueRef: ActorRef) extends Actor with StrictLogging {
  var cleanup: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    cleanup = context.system.scheduler.schedule(10.seconds, 10.seconds, self, ResetViableTasksDelays)
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

  /**
    * If an app gets removed or updated, the delay should be reset. If
    * an app is considered viable, the delay should be reset too. We
    * check and reset viable tasks' delays periodically.
    */
  private[this] def receiveCleanup: Receive = {
    case ResetViableTasksDelays =>
      rateLimiter.resetDelaysOfViableTasks()
  }

  private[this] def receiveDelayOps: Receive = {
    case GetDelay(runSpec) =>
      sender() ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))

    case AddDelay(runSpec) =>
      rateLimiter.addDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))

    case DecreaseDelay(runSpec) => // ignore for now

    case ResetDelay(runSpec) =>
      rateLimiter.resetDelay(runSpec)
      launchQueueRef ! DelayUpdate(runSpec, rateLimiter.getDeadline(runSpec))
      sender() ! ResetDelayResponse
  }
}
