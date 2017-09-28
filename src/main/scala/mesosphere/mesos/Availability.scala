package mesosphere.mesos

import java.time.Clock

import mesosphere.marathon.RichClock
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ DurationInfo, Offer }

import scala.concurrent.duration._

object Availability {

  def offerAvailable(offer: Offer, drainingTime: FiniteDuration)(implicit clock: Clock): Boolean = {
    val now = clock.now()
    if (offerHasUnavailability(offer)) {
      val start: Timestamp = offer.getUnavailability.getStart

      if (currentlyInDrainingState(now, start, drainingTime)) {
        isAgentOutsideUnavailabilityWindow(offer, start, now)
      } else true
    } else true
  }

  private def currentlyInDrainingState(now: Timestamp, start: Timestamp, drainingTime: FiniteDuration) = {
    now.after(start - drainingTime)
  }

  private def offerHasUnavailability(offer: Offer) = {
    offer.hasUnavailability && offer.getUnavailability.hasStart
  }

  private def isAgentOutsideUnavailabilityWindow(offer: Offer, start: Timestamp, now: Timestamp) = {
    offer.getUnavailability.hasDuration && now.after(start + offer.getUnavailability.getDuration.toDuration)
  }

  /**
    * Convert Mesos DurationInfo to FiniteDuration.
    *
    * @return FiniteDuration for DurationInfo
    */
  implicit class DurationInfoHelper(val di: DurationInfo) extends AnyVal {
    def toDuration: FiniteDuration = FiniteDuration(di.getNanoseconds, NANOSECONDS)
  }

}
