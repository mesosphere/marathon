package mesosphere.marathon
package core.flow

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.flow.impl.OfferMatcherLaunchTokensActor
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager

/**
  * This module contains code for managing the flow/backpressure of the application.
  */
class FlowModule(leadershipModule: LeadershipModule) extends StrictLogging {

  /**
    * Refills the launch tokens of the OfferMatcherManager periodically. See [[LaunchTokenConfig]] for configuration.
    *
    * Also adds a launch token to the OfferMatcherManager for every update we get about a new running tasks.
    *
    * The reasoning is that getting infos about running tasks signals that the Mesos infrastructure is working
    * and not yet completely overloaded.
    */
  def refillOfferMatcherManagerLaunchTokens(conf: LaunchTokenConfig, offerMatcherManager: OfferMatcherManager): Unit = {
    lazy val offerMatcherLaunchTokensProps = OfferMatcherLaunchTokensActor.props(
      conf,
      offerMatcherManager
    )
    leadershipModule.startWhenLeader(offerMatcherLaunchTokensProps, "offerMatcherLaunchTokens")
  }
}
