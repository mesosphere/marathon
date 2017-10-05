package mesosphere.marathon
package tasks

import org.apache.mesos.Protos.Offer

object OfferUtil {
  def region(offer: Offer): Option[String] = if (offer.hasDomain) {
    val domain = offer.getDomain
    if (domain.hasFaultDomain) {
      // region and name are marked as required in the protobuf definition
      Some(domain.getFaultDomain.getRegion.getName)
    } else None
  } else None

  def zone(offer: Offer): Option[String] =
    if (offer.hasDomain) {
      val domain = offer.getDomain
      if (domain.hasFaultDomain) {
        // zone and name are marked as required in the protobuf definition
        Some(domain.getFaultDomain.getZone.getName)
      } else None
    } else None
}
