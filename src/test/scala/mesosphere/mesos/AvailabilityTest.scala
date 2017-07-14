package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.{ DurationInfo, Offer, TimeInfo, Unavailability }

import scala.concurrent.duration._

class AvailabilityTest extends UnitTest {

  implicit val clock = Clock()
  val now = clock.now()

  "Availability" should {
    "drop offer from nodes in maintenance" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability().build(), FiniteDuration(0, SECONDS)) shouldBe false
      Availability.offerAvailable(makeBasicOfferWithUnavailability(now - Duration(1, HOURS), Duration(1, DAYS)).build(), FiniteDuration(0, SECONDS)) shouldBe false
    }
    "accept offers from nodes not in maintenance" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(now + Duration(1, HOURS), Duration(1, DAYS)).build(), FiniteDuration(0, SECONDS)) shouldBe true
      Availability.offerAvailable(MarathonTestHelper.makeBasicOffer().build(), Duration(0, SECONDS)) shouldBe true
      Availability.offerAvailable(makeBasicOfferWithUnavailability(now - Duration(1, DAYS), Duration(1, HOURS)).build(), FiniteDuration(0, SECONDS)) shouldBe true
    }
    "drop offers {drainingTime} seconds before node maintenance starts" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(now + Duration(200, SECONDS), Duration(1, DAYS)).build(), Duration(300, SECONDS)) shouldBe false
    }
    "drop offer when maintenance with infinite duration" in {
      Availability.offerAvailable(makeBasicOfferWithUnavailability(now - Duration(1, HOURS)).build(), FiniteDuration(0, SECONDS)) shouldBe false
    }
  }

  def makeBasicOfferWithUnavailability(startTime: Timestamp = now, duration: Duration = Duration.Inf): Offer.Builder = {
    val unavailableOfferBuilder = Unavailability.newBuilder()
      .setStart(TimeInfo.newBuilder().setNanoseconds(startTime.nanos))

    if (duration.isFinite()) {
      unavailableOfferBuilder.setDuration(DurationInfo.newBuilder().setNanoseconds(duration.toNanos))
    }

    MarathonTestHelper.makeBasicOffer().setUnavailability(unavailableOfferBuilder.build())
  }
}
