package mesosphere.marathon.tasks

object OfferReviverDummy {
  def apply(): OfferReviver = new OfferReviverDummy()
}

class OfferReviverDummy extends OfferReviver {
  override def reviveOffers(): Unit = {}
}
