package mesosphere.marathon
package core.matcher.manager

import akka.actor.Cancellable
import akka.stream.scaladsl.Keep
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import java.time.Clock

import akka.actor.ActorRef
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.impl.{OfferMatcherManagerActor, OfferMatcherManagerActorMetrics, OfferMatcherManagerDelegate}
import mesosphere.marathon.state.Region
import mesosphere.marathon.stream.Subject

import scala.util.Random

/**
  * This module provides a globalOfferMatcher which delegates to all OfferMatchers which registered themselves
  * at the subOfferMatcherManager. It also exports the offersWanted observable for flow control.
  */
class OfferMatcherManagerModule(
    clock: Clock, random: Random,
    offerMatcherConfig: OfferMatcherManagerConfig,
    leadershipModule: LeadershipModule,
    localRegion: () => Option[Region],
    actorName: String = "offerMatcherManager")(implicit val materializer: Materializer) {

  val (inputOffersWanted, offersWanted) = Source.queue[Boolean](16, OverflowStrategy.backpressure)
    .toMat(Subject[Boolean](16, OverflowStrategy.dropHead))(Keep.both)
    .run

  private[this] lazy val offerMatcherManagerMetrics = new OfferMatcherManagerActorMetrics()

  private[this] val offerMatcherMultiplexer: ActorRef = {
    val props = OfferMatcherManagerActor.props(
      offerMatcherManagerMetrics, random, clock, offerMatcherConfig, inputOffersWanted)
    leadershipModule.startWhenLeader(props, actorName)
  }

  /**
    * Signals `true` if we are interested in (new) offers, signals `false` if we are currently not interested in
    * offers.
    */
  val globalOfferMatcherWantsOffers: Source[Boolean, Cancellable] = offersWanted
  val globalOfferMatcher: OfferMatcher = new ActorOfferMatcher(offerMatcherMultiplexer, None)
  val subOfferMatcherManager: OfferMatcherManager = new OfferMatcherManagerDelegate(offerMatcherMultiplexer)
}
