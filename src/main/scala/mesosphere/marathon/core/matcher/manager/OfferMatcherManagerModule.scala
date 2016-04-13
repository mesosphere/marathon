package mesosphere.marathon.core.matcher.manager

import akka.actor.ActorRef
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.impl.{
  OfferMatcherManagerActor,
  OfferMatcherManagerActorMetrics,
  OfferMatcherManagerDelegate
}
import mesosphere.marathon.metrics.Metrics
import rx.lang.scala.subjects.BehaviorSubject
import rx.lang.scala.{ Observable, Subject }

import scala.util.Random

/**
  * This module provides a globalOfferMatcher which delegates to all OfferMatchers which registered themselves
  * at the subOfferMatcherManager. It also exports the offersWanted observable for flow control.
  */
class OfferMatcherManagerModule(
    clock: Clock, random: Random, metrics: Metrics,
    offerMatcherConfig: OfferMatcherManagerConfig,
    leadershipModule: LeadershipModule) {

  private[this] lazy val offersWanted: Subject[Boolean] = BehaviorSubject[Boolean](false)

  private[this] lazy val offerMatcherManagerMetrics = new OfferMatcherManagerActorMetrics(metrics)

  private[this] val offerMatcherMultiplexer: ActorRef = {
    val props = OfferMatcherManagerActor.props(
      offerMatcherManagerMetrics, random, clock, offerMatcherConfig, offersWanted)
    leadershipModule.startWhenLeader(props, "offerMatcherManager")
  }

  /**
    * Signals `true` if we are interested in (new) offers, signals `false` if we are currently not interested in
    * offers.
    */
  val globalOfferMatcherWantsOffers: Observable[Boolean] = offersWanted
  val globalOfferMatcher: OfferMatcher = new ActorOfferMatcher(clock, offerMatcherMultiplexer, None)
  val subOfferMatcherManager: OfferMatcherManager = new OfferMatcherManagerDelegate(offerMatcherMultiplexer)
}
