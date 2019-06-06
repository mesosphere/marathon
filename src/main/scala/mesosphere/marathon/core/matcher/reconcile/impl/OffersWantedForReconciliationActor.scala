package mesosphere.marathon
package core.matcher.reconcile.impl

import java.time.Clock

import akka.actor.{Actor, Cancellable, Props}
import akka.event.{EventStream, LoggingReceive}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.event.DeploymentStepSuccess
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.deployment.StopApplication
import rx.lang.scala.Observer

import scala.concurrent.duration._

/**
  * The OfferMatcherReconciler will check every incoming offer.
  * Sometimes the existing logic for driving offer revives is not sufficient, e.g.
  * when a resident app terminates, we need to clean up all left-over reservations.
  * In this case, this actor indicates additional demand for offers.
  */
private[reconcile] object OffersWantedForReconciliationActor {
  def props(
    reviveOffersConfig: ReviveOffersConfig,
    clock: Clock,
    eventStream: EventStream,
    offersWanted: Observer[Boolean]): Props =
    Props(new OffersWantedForReconciliationActor(
      reviveOffersConfig,
      clock, eventStream,
      offersWanted
    ))

  private case class RequestOffers(reason: String)
  case object CancelInterestInOffers
}

private[reconcile] class OffersWantedForReconciliationActor(
    reviveOffersConfig: ReviveOffersConfig,
    clock: Clock,
    eventStream: EventStream,
    offersWanted: Observer[Boolean]) extends Actor with StrictLogging {

  /** Make certain that the normal number of revives that the user specified will be executed. */
  private[this] val interestDuration =
    (reviveOffersConfig.minReviveOffersInterval() * (reviveOffersConfig.reviveOffersRepetitions() + 0.5)).millis

  override def preStart(): Unit = {
    super.preStart()

    eventStream.subscribe(self, classOf[DeploymentStepSuccess])

    logger.info("Scheduling request for offers after becoming leader")
    self ! OffersWantedForReconciliationActor.RequestOffers("becoming leader")
  }

  override def postStop(): Unit = {
    eventStream.unsubscribe(self)

    super.postStop()
  }

  override def receive: Receive = unsubscribedToOffers

  private[this] def handleRequestOfferIndicators: Receive = {
    case success: DeploymentStepSuccess =>
      val terminatedResidentApps = success.currentStep.actions.view.collect {
        case StopApplication(app) if app.isResident => app
      }

      if (terminatedResidentApps.nonEmpty) {
        val terminatedResidentAppsString = terminatedResidentApps.map(_.id).mkString(", ")
        self ! OffersWantedForReconciliationActor.RequestOffers(
          s"terminated resident app(s) $terminatedResidentAppsString"
        )
      }
  }

  private[this] def subscribedToOffers(reason: String): Receive = {
    // exit this state after the timeout
    val timeout = scheduleCancelInterestInOffers

    // notify the ReviveOffersActor via this observable that the OfferMatcherReconciler needs offers
    offersWanted.onNext(true)
    val until: Timestamp = clock.now() + interestDuration
    logger.info(s"interested in offers for reservation reconciliation because of $reason (until $until)")

    LoggingReceive.withLabel("subscribedToOffers") {
      handleRequestOfferIndicators orElse {
        case OffersWantedForReconciliationActor.CancelInterestInOffers =>
          logger.info("Canceling interest in offers")
          timeout.cancel()
          context.become(unsubscribedToOffers)
        case OffersWantedForReconciliationActor.RequestOffers(newReason) =>
          logger.info("Received new RequestOffers; re-entering subscribedToOffers")
          timeout.cancel()
          context.become(subscribedToOffers(newReason))
      }: Receive
    }
  }

  protected def scheduleCancelInterestInOffers: Cancellable = {
    context.system.scheduler.scheduleOnce(
      interestDuration, self, OffersWantedForReconciliationActor.CancelInterestInOffers
    )(context.dispatcher)
  }

  private[this] def unsubscribedToOffers: Receive = LoggingReceive.withLabel("unsubscribedToOffers") {
    offersWanted.onNext(false)
    logger.info("No interest in offers for reservation reconciliation.")

    handleRequestOfferIndicators orElse {
      case OffersWantedForReconciliationActor.CancelInterestInOffers => //ignore
      case OffersWantedForReconciliationActor.RequestOffers(reason) =>
        context.become(subscribedToOffers(reason))
    }: Receive
  }
}
