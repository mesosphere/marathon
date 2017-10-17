package mesosphere.marathon
package core.flow

import java.time.Clock

import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.flow.impl.{ OfferReviverDelegate, OfferMatcherLaunchTokensActor, ReviveOffersActor }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import org.slf4j.LoggerFactory
import rx.lang.scala.Observable

/**
  * This module contains code for managing the flow/backpressure of the application.
  */
class FlowModule(leadershipModule: LeadershipModule) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * Call `reviveOffers` of the `SchedulerDriver` interface whenever there is new interest
    * in offers by an OfferMatcher. There is some logic to prevent calling `reviveOffers` to often.
    * See [[ReviveOffersConfig]] for configuring this.
    *
    * @param offersWanted An observable which emits `true` whenever offers are currently wanted or offer interest
    *                     has changed. It should emit `false` when interest in offers is lost.
    * @param driverHolder The driverHolder containing the driver on which to call `reviveOffers`.
    * @return an offer reviver that allows explicit calls to reviveOffers
    */
  def maybeOfferReviver(
    clock: Clock,
    conf: ReviveOffersConfig,
    marathonEventStream: EventStream,
    offersWanted: Observable[Boolean],
    driverHolder: MarathonSchedulerDriverHolder): Option[OfferReviver] = {

    if (conf.reviveOffersForNewApps()) {
      lazy val reviveOffersActor = ReviveOffersActor.props(
        clock, conf, marathonEventStream,
        offersWanted, driverHolder
      )
      val actorRef = leadershipModule.startWhenLeader(reviveOffersActor, "reviveOffersWhenWanted")
      log.info("Calling reviveOffers is enabled. Use --disable_revive_offers_for_new_apps to disable.")
      Some(new OfferReviverDelegate(actorRef))
    } else {
      log.info("Calling reviveOffers is disabled. Use --revive_offers_for_new_apps to enable.")
      None
    }
  }

  /**
    * Refills the launch tokens of the OfferMatcherManager periodically. See [[LaunchTokenConfig]] for configuration.
    *
    * Also adds a launch token to the OfferMatcherManager for every update we get about a new running tasks.
    *
    * The reasoning is that getting infos about running tasks signals that the Mesos infrastructure is working
    * and not yet completely overloaded.
    */
  def refillOfferMatcherManagerLaunchTokens(
    conf: LaunchTokenConfig,
    offerMatcherManager: OfferMatcherManager): Unit =
    {
      lazy val offerMatcherLaunchTokensProps = OfferMatcherLaunchTokensActor.props(
        conf, offerMatcherManager
      )
      leadershipModule.startWhenLeader(offerMatcherLaunchTokensProps, "offerMatcherLaunchTokens")

    }
}
