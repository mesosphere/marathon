package mesosphere.marathon.core.flow

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.impl.{ OfferMatcherLaunchTokensActor, ReviveOffersActor }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskStatusObservables
import rx.lang.scala.Observable

/**
  * This module contains code for managing the flow/backpressure of the application.
  */
class FlowModule(leadershipModule: LeadershipModule) {

  /**
    * Call `reviveOffers` of the `SchedulerDriver` interface whenever there is new interest
    * in offers by an OfferMatcher. There is some logic to prevent calling `reviveOffers` to often.
    * See [[ReviveOffersConfig]] for configuring this.
    *
    * @param offersWanted An observable which emits `true` whenever offers are currently wanted or offer interest
    *                     has changed. It should emit `false` when interest in offers is lost.
    * @param driverHolder The driverHolder containing the driver on which to call `reviveOffers`.
    */
  def reviveOffersWhenOfferMatcherManagerSignalsInterest(
    clock: Clock, conf: ReviveOffersConfig,
    offersWanted: Observable[Boolean], driverHolder: MarathonSchedulerDriverHolder): Unit = {
    lazy val reviveOffersActor = ReviveOffersActor.props(
      clock, conf,
      offersWanted, driverHolder
    )
    leadershipModule.startWhenLeader(reviveOffersActor, "reviveOffersWhenWanted")
  }

  /**
    * Refills the launch tokens of the OfferMatcherManager periodically. See [[LaunchTokenConfig]] for configuration.
    *
    * Also adds a launch token to othe OfferMatcherManager for every update we get about a new running tasks.
    *
    * The reasoning is that getting infos about running tasks signals that the Mesos infrastructure is working
    * and not yet completely overloaded.
    */
  def refillOfferMatcherManagerLaunchTokens(
    conf: LaunchTokenConfig,
    taskStatusObservables: TaskStatusObservables,
    offerMatcherManager: OfferMatcherManager): Unit =
    {
      lazy val offerMatcherLaunchTokensProps = OfferMatcherLaunchTokensActor.props(
        conf, taskStatusObservables, offerMatcherManager
      )
      leadershipModule.startWhenLeader(offerMatcherLaunchTokensProps, "offerMatcherLaunchTokens")

    }
}
