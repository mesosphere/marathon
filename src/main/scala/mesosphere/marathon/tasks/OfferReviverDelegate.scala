package mesosphere.marathon.tasks

import akka.actor.ActorRef

object OfferReviverDelegate {
  case object ReviveOffers
}

class OfferReviverDelegate(offerReviverRef: ActorRef) extends OfferReviver {
  override def reviveOffers(): Unit = offerReviverRef ! OfferReviverDelegate.ReviveOffers
}
