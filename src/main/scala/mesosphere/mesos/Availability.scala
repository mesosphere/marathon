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
          now.after(start + offer.getUnavailability.getDuration)
      } else true
    } else true
  }

  /**
    * Convert Mesos DurationInfo to FiniteDuration.
    * @return FiniteDuration for DurationInfo
    */
  implicit def toDuration(durationInfo: DurationInfo): FiniteDuration = {
    FiniteDuration(durationInfo.getNanoseconds, NANOSECONDS)
  }
}
