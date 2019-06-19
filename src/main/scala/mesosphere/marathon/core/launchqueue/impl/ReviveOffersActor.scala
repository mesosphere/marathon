package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{Actor, Props, Stash}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstancesSnapshot}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}

import scala.concurrent.duration._

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

  implicit val mat = ActorMaterializer()(context)

  override def preStart(): Unit = {
    super.preStart()

    val done = ReviveOffersStreamLogic.suppressAndReviveStream(
      instanceUpdates,
      delayedConfigRefs = rateLimiterUpdates.via(ReviveOffersStreamLogic.activelyDelayedRefs),
      minReviveOffersInterval = minReviveOffersInterval,
      driverHolder = driverHolder)
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

    done.onComplete { result =>
      logger.error(s"Unexpected termination of revive stream; ${result}")
    }(context.dispatcher)

  }

  override def receive: Receive = LoggingReceive {
    case Revive => reviveOffers()
    case Suppress => suppressOffers()
    case Done => context.stop(self)
    case other =>
      logger.info(s"Unexpected message $other")
  }

  def reviveOffers(): Unit = {
    reviveCountMetric.increment()
    logger.info("Sending revive")
    driverHolder.driver.foreach(_.reviveOffers())
  }

  def suppressOffers(): Unit = {
    suppressCountMetric.increment()
    logger.info("Sending suppress")
    driverHolder.driver.foreach(_.suppressOffers())
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
