package mesosphere.mesos

import java.util.concurrent.TimeUnit

import org.apache.mesos.Protos.Offer

object Availability {

  def offerAvailable(offer: Offer, drainingTime: Long): Boolean = {
    var unavailability = offer.hasUnavailability
    var now = System.nanoTime()
    if (offer.hasUnavailability && offer.getUnavailability.hasStart) {
      val start = offer.getUnavailability.getStart.getNanoseconds
      if (now >= (start - TimeUnit.SECONDS.toNanos(drainingTime))) {
        if (offer.getUnavailability.hasDuration) {
          return start + (offer.getUnavailability.getDuration.getNanoseconds) < (now)
        } else {
          return false
        }
      }
    }
    return true
  }

}
