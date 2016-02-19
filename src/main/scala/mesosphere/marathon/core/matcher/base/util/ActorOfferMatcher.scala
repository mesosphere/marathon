package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedTaskOps
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.Offer

import scala.concurrent.Future

/**
  * Provides a thin wrapper around an OfferMatcher implemented as an actors.
  */
class ActorOfferMatcher(clock: Clock,
                        actorRef: ActorRef,
                        override val precedenceFor: Option[PathId]) extends OfferMatcher {
  def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTaskOps] = {
    implicit val timeout: Timeout = clock.now().until(deadline)
    val answerFuture = actorRef ? ActorOfferMatcher.MatchOffer(deadline, offer)
    answerFuture.mapTo[MatchedTaskOps]
  }

  override def toString: String = s"ActorOfferMatcher($actorRef)"
}

object ActorOfferMatcher {
  /**
    * Send to an offer matcher to request a match.
    *
    * This should always be replied to with a LaunchTasks message.
    */
  case class MatchOffer(matchingDeadline: Timestamp, remainingOffer: Offer)
}
