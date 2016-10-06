package mesosphere.marathon.core.matcher.base.util

import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedInstanceOps }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.Offer

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Wraps multiple offer matchers and returns the first non-empty match or (if all are empty) the last empty match.
  */
class StopOnFirstMatchingOfferMatcher(chained: OfferMatcher*) extends OfferMatcher {
  override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {
    chained.foldLeft(Future.successful(MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = false))) {
      case (matchedFuture, nextMatcher) =>
        matchedFuture.flatMap { matched =>
          if (matched.ops.isEmpty) nextMatcher.matchOffer(deadline, offer)
          else matchedFuture
        }(ExecutionContext.global)
    }
  }
}

object StopOnFirstMatchingOfferMatcher {
  def apply(chained: OfferMatcher*): StopOnFirstMatchingOfferMatcher = new StopOnFirstMatchingOfferMatcher(chained: _*)
}
