package mesosphere.marathon.core.flow

trait OfferReviver {
  def reviveOffers(): Unit
}
