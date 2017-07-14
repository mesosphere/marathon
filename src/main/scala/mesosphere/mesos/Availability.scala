package mesosphere.mesos

import mesosphere.marathon.core.base.Clock
import org.apache.mesos.Protos.Offer

import scala.concurrent.duration._

object Availability {

  def offerAvailable(offer: Offer, drainingTime: Duration): Boolean = {
    lazy val clock: Clock = Clock()
    val drainingTimeNanoseconds = drainingTime.toNanos
    if (offer.hasUnavailability && offer.getUnavailability.hasStart) {
      val start = offer.getUnavailability.getStart.getNanoseconds
      if (clock.now().millis >= (start - drainingTimeNanoseconds)) {
        offer.getUnavailability.hasDuration &&
          start + offer.getUnavailability.getDuration.getNanoseconds < clock.now().millis
      } else true
    } else true
  }

}
