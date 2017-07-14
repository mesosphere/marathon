package mesosphere.mesos

import java.util.concurrent.TimeUnit

import mesosphere.UnitTest
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.{ DurationInfo, Offer, TimeInfo, Unavailability }

import scala.concurrent.duration._

class AvailabilityTest extends UnitTest {

  lazy val clock: Clock = Clock()

  "Availability" should {
    "drop offer from nodes in maintenance" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(clock.now().millis + (TimeUnit.HOURS.toNanos(1)), TimeUnit.DAYS.toNanos(1)).build(), Duration(0, SECONDS)) shouldBe true
      Availability.offerAvailable(makeBasicOfferWithUnavailability().build(), Duration(0, SECONDS)) shouldBe false
      Availability.offerAvailable(makeBasicOfferWithUnavailability(clock.now().millis - (TimeUnit.HOURS.toNanos(1)), TimeUnit.DAYS.toNanos(1)).build(), Duration(0, SECONDS)) shouldBe false
    }
    "accept offers from nodes not in maintenance" in {
      Availability.offerAvailable(MarathonTestHelper.makeBasicOffer().build(), Duration(0, SECONDS)) shouldBe true
      Availability.offerAvailable(makeBasicOfferWithUnavailability(clock.now().millis - (TimeUnit.DAYS.toNanos(1)), TimeUnit.HOURS.toNanos(1)).build(), Duration(0, SECONDS)) shouldBe true
    }
    "drop offers {drainingTime} seconds before node maintenance starts" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(clock.now().millis + (TimeUnit.SECONDS.toNanos(200)), TimeUnit.DAYS.toNanos(1)).build(), Duration(300, SECONDS)) shouldBe false
    }
    "drop offer when maintenance with infinite duration" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(clock.now().millis - (TimeUnit.HOURS.toNanos(1))).build(), Duration(0, SECONDS)) shouldBe false
    }
  }

  def makeBasicOfferWithUnavailability(startTimeInNano: Long = clock.now().millis, durationInNano: Long = -1l): Offer.Builder = {
    val unavailableOfferBuilder = Unavailability.newBuilder()
      .setStart(TimeInfo.newBuilder().setNanoseconds(startTimeInNano))

    if (durationInNano > 0) {
      unavailableOfferBuilder.setDuration(DurationInfo.newBuilder().setNanoseconds(durationInNano))
    }

    MarathonTestHelper.makeBasicOffer().setUnavailability(unavailableOfferBuilder.build())
  }
}
