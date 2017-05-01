package mesosphere.marathon
package core.flow

trait OfferReviver {
  def reviveOffers(): Unit
}
