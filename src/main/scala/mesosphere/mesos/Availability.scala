package mesosphere.mesos

import java.time.Clock

import mesosphere.marathon.RichClock
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ DurationInfo, Offer }

import scala.concurrent.duration._
import scala.language.implicitConversions

object Availability {

  def offerAvailable(offer: Offer, drainingTime: FiniteDuration)(implicit clock: Clock): Boolean = {
    val now = clock.now()
    if (offer.hasUnavailability && offer.getUnavailability.hasStart) {
      val start: Timestamp = offer.getUnavailability.getStart
      if (now.after(start - drainingTime)) {
        offer.getUnavailability.hasDuration &&
          now.after(start + offer.getUnavailability.getDuration.toDuration)
      } else true
    } else true
  }

  /**
    * Convert Mesos DurationInfo to FiniteDuration.
    * @return FiniteDuration for DurationInfo
    */
  implicit class DurationInfoHelper(val di: DurationInfo) extends AnyVal {
    def toDuration: FiniteDuration = FiniteDuration(di.getNanoseconds, NANOSECONDS)
  }
}
