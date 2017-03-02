package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import akka.pattern.{ AskTimeoutException, ask }
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.Offer

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Provides a thin wrapper around an OfferMatcher implemented as an actors.
  */
class ActorOfferMatcher(
    clock: Clock,
    actorRef: ActorRef,
    override val precedenceFor: Option[PathId]) extends OfferMatcher with StrictLogging {
  def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {
    import mesosphere.util.CallerThreadExecutionContext.callerThreadExecutionContext
    implicit val timeout: Timeout = clock.now().until(deadline)
    if (timeout.duration > ActorOfferMatcher.MinimalOfferComputationTime) {
      val answerFuture = actorRef ? ActorOfferMatcher.MatchOffer(deadline, offer)
      answerFuture.mapTo[MatchedInstanceOps].recover {
        case _: AskTimeoutException =>
          logger.warn(s"Could not process offer '${offer.getId.getValue}' within ${timeout.duration.toMillis} millis. (See --offer_matching_timeout)")
          MatchedInstanceOps(offer.getId)
      }
    } else {
      // if deadline is exceeded return no match
      logger.warn(s"Could not process offer '${offer.getId.getValue}' within ${timeout.duration.toMillis} millis. (See --offer_matching_timeout)")
      Future.successful(MatchedInstanceOps(offer.getId))
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
    */
  case class MatchOffer(matchingDeadline: Timestamp, remainingOffer: Offer)
}
