package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.Status.Success
import akka.actor.{Actor, Props, Stash, Status}
import akka.pattern.pipe
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}

import scala.concurrent.duration._
import scala.util.Failure

sealed trait Op
case object Revive extends Op
case object Suppress extends Op

class ReviveOffersActor(
    metrics: Metrics,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with Stash with StrictLogging {

  private[this] val reviveCountMetric: Counter = metrics.counter("mesos.calls.revive")
  private[this] val suppressCountMetric: Counter = metrics.counter("mesos.calls.suppress")

  import context.dispatcher
  implicit val mat = ActorMaterializer()(context)

  override def preStart(): Unit = {
    super.preStart()

    val done = ReviveOffersStreamLogic.suppressAndReviveStream(
      instanceUpdates,
      delayedConfigRefs = rateLimiterUpdates.via(ReviveOffersStreamLogic.activelyDelayedRefs),
      minReviveOffersInterval = minReviveOffersInterval)
      .runWith(Sink.foreach {
        case Revive =>
          reviveCountMetric.increment()
          logger.info("Sending revive")
          driverHolder.driver.foreach(_.reviveOffers())
        case Suppress =>
          suppressCountMetric.increment()
          logger.info("Sending suppress")
          driverHolder.driver.foreach(_.suppressOffers())
      })

    done.pipeTo(self)

  }

  override def receive: Receive = LoggingReceive {
    case Status.Failure(ex) =>
      logger.error("Unexpected termination of revive stream", ex)
      throw ex
    case Done =>
      logger.error(s"Unexpected successful termination of revive stream")
  }

}

object ReviveOffersActor {
  def props(
    metrics: Metrics,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(metrics, minReviveOffersInterval, instanceUpdates, rateLimiterUpdates, driverHolder))
  }
}
