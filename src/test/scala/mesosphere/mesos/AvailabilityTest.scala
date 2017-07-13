package mesosphere.mesos

import java.util.concurrent.TimeUnit

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.{ DurationInfo, Offer, TimeInfo, Unavailability }

class AvailabilityTest extends UnitTest {

  "Availability" should {
    "drop offer from nodes in maintenance" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability().build(), 0) shouldBe false
      Availability.offerAvailable(makeBasicOfferWithUnavailability(System.nanoTime() - (TimeUnit.HOURS.toNanos(1)), TimeUnit.DAYS.toNanos(1)).build(), 0) shouldBe false

    }
    "accept offers from nodes not in maintenance" in {
      Availability.offerAvailable(MarathonTestHelper.makeBasicOffer().build(), 0) shouldBe true
      Availability.offerAvailable(makeBasicOfferWithUnavailability(System.nanoTime() + (TimeUnit.HOURS.toNanos(1)), TimeUnit.DAYS.toNanos(1)).build(), 0) shouldBe true
      Availability.offerAvailable(makeBasicOfferWithUnavailability(System.nanoTime() - (TimeUnit.DAYS.toNanos(1)), TimeUnit.HOURS.toNanos(1)).build(), 0) shouldBe true
    }
    "drop offers {drainingTime} seconds before node maintenance starts" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(System.nanoTime() + (TimeUnit.SECONDS.toNanos(200)), TimeUnit.DAYS.toNanos(1)).build(), 300) shouldBe false
    }
  }

  def makeBasicOfferWithUnavailability(startTimeInNano: Long = System.nanoTime(), durationInNano: Long = -1l): Offer.Builder = {
    var unavailableOfferBuilder = Unavailability.newBuilder()
      .setStart(TimeInfo.newBuilder().setNanoseconds(startTimeInNano))

    if (durationInNano > 0) {
      unavailableOfferBuilder.setDuration(DurationInfo.newBuilder().setNanoseconds(durationInNano))
    }

    MarathonTestHelper.makeBasicOffer().setUnavailability(unavailableOfferBuilder.build())
  }
}
