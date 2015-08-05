package mesosphere.marathon.tasks

trait OfferReviver {
  def reviveOffers(): Unit
}
