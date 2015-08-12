package mesosphere.marathon.core.flow.impl

import akka.actor.ActorRef
import mesosphere.marathon.core.flow.OfferReviver

object OfferReviverDelegate {
  case object ReviveOffers
}

class OfferReviverDelegate(offerReviverRef: ActorRef) extends OfferReviver {
  override def reviveOffers(): Unit = offerReviverRef ! OfferReviverDelegate.ReviveOffers
}
