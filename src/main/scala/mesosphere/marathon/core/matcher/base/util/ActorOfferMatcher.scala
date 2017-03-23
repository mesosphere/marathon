package mesosphere.marathon
package core.matcher.base.util

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.util._
import mesosphere.marathon.util.{ Timeout, TimeoutException }
import org.apache.mesos.Protos.Offer

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

/**
  * Provides a thin wrapper around an OfferMatcher implemented as an actors.
  *
  * @param actorRef Reference to actor that matches offers.
  * @param precedenceFor Defines which matcher receives offers first. See [[mesosphere.marathon.core.matcher.base.OfferMatcher.precedenceFor]].
  */
class ActorOfferMatcher(actorRef: ActorRef, override val precedenceFor: Option[PathId])(implicit scheduler: akka.actor.Scheduler)
    extends OfferMatcher with StrictLogging {

  def matchOffer(now: Timestamp, deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val timeout: FiniteDuration = now.until(deadline)

    if (timeout <= ActorOfferMatcher.MinimalOfferComputationTime) {
      // if deadline is exceeded return no match
      logger.warn(s"Could not process offer '${offer.getId.getValue}' within ${timeout.toHumanReadable}. (See --offer_matching_timeout)")
      Future.successful(MatchedInstanceOps.noMatch(offer.getId))
    } else {

      val p = Promise[MatchedInstanceOps]()
      actorRef ! ActorOfferMatcher.MatchOffer(deadline, offer, p)

      Timeout(timeout)(p.future).recover {
        case e: TimeoutException =>
          logger.warn(s"Could not process offer '${offer.getId.getValue}' within ${timeout.toHumanReadable}. (See --offer_matching_timeout)")
          MatchedInstanceOps.noMatch(offer.getId)
      }
    }
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
    * @param matchingDeadline Don't match after deadline.
    * @param remainingOffer Part of the offer that has not been matched.
    * @param promise The promise to fullfil with match.
    */
  case class MatchOffer(matchingDeadline: Timestamp, remainingOffer: Offer, promise: Promise[MatchedInstanceOps])
}
