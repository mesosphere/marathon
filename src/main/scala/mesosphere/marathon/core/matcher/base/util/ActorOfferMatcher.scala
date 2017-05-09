package mesosphere.marathon
package core.matcher.base.util

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.Offer

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

/**
  * Provides a thin wrapper around an OfferMatcher implemented as an actors.
  *
  * @param actorRef Reference to actor that matches offers.
  * @param precedenceFor Defines which matcher receives offers first. See [[mesosphere.marathon.core.matcher.base.OfferMatcher.precedenceFor]].
  */
class ActorOfferMatcher(actorRef: ActorRef, override val precedenceFor: Option[PathId])(implicit scheduler: akka.actor.Scheduler)
    extends OfferMatcher with StrictLogging {

  def matchOffer(offer: Offer): Future[MatchedInstanceOps] = {
    val p = Promise[MatchedInstanceOps]()
    actorRef ! ActorOfferMatcher.MatchOffer(offer, p)
    p.future
  }

  override def toString: String = s"ActorOfferMatcher($actorRef)"
}

object ActorOfferMatcher {

  // Do not start a offer matching if there is less time than this minimal time
  // Optimization to prevent timeouts
  val MinimalOfferComputationTime: FiniteDuration = 50.millis

  /**
    * Send to an offer matcher to request a match.
    *
    * This should always be replied to with a LaunchTasks message.
    * TODO(jdef) pods will probably require a non-LaunchTasks message
    *
    * @param remainingOffer Part of the offer that has not been matched.
    * @param promise The promise to fullfil with match.
    */
  case class MatchOffer(remainingOffer: Offer, promise: Promise[MatchedInstanceOps])
}
