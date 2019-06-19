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
    reviveOffersRepetitions: Int,
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
      instanceUpdates.alsoTo(reservationReconciliation),
      delayedConfigRefs = rateLimiterUpdates.via(ReviveOffersStreamLogic.activelyDelayedRefs),
      minReviveOffersInterval = minReviveOffersInterval,
      reviveOffersRepetitions = reviveOffersRepetitions)
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

  /**
    * Revives if a resident instances was deleted.
    *
    * Currently Marathon uses [[InstanceTrackerDelegate.forceExpunge]] when a run spec with resident instances
    * is removed. Thus Marathon looses all knowledge of any reservations to these instances. The [[OfferMatcherReconciler]]
    * is supposed to filter offers for these reservations and destroy them if no related instance is known.
    *
    * This flow logic emits one revive call to trigger an offer with said reservations to be destroyed. There is no
    * guarantee that the reservation is destroyed.
    *
    * @return A simple flow that calls revive if a resident instances was deleted.
    */
  def reservationReconciliation: Sink[(InstancesSnapshot, Source[InstanceChange, NotUsed]), NotUsed] = {
    Flow[(InstancesSnapshot, Source[InstanceChange, NotUsed])]
      .flatMapConcat { pair =>
        pair._2.filter {
          case InstanceDeleted(instance, _, _) if instance.reservation.nonEmpty => true
          case _ => false
        }
      }.toMat(Sink.foreach{ _ =>
        logger.info("Sending revive to reconcile reservation")
        driverHolder.driver.foreach(_.reviveOffers())
      })(Keep.left)
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
    reviveOffersRepetitions: Int,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new ReviveOffersActor(metrics, reviveOffersRepetitions, minReviveOffersInterval, instanceUpdates, rateLimiterUpdates, driverHolder))
  }
}
