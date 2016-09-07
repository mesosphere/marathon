package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.Offer

import scala.concurrent.Future

/**
  * Provides a thin wrapper around an OfferMatcher implemented as an actors.
  */
class ActorOfferMatcher(
    clock: Clock,
    actorRef: ActorRef,
    override val precedenceFor: Option[PathId]) extends OfferMatcher {
  def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {
    implicit val timeout: Timeout = clock.now().until(deadline)
    val answerFuture = actorRef ? ActorOfferMatcher.MatchOffer(deadline, offer)
    answerFuture.mapTo[MatchedInstanceOps]
  }

  override def toString: String = s"ActorOfferMatcher($actorRef)"
}

object ActorOfferMatcher {
  /**
    * Send to an offer matcher to request a match.
    *
    * This should always be replied to with a LaunchTasks message.
    * TODO(jdef) pods will probably require a non-LaunchTasks message
    */
  case class MatchOffer(matchingDeadline: Timestamp, remainingOffer: Offer)
}
