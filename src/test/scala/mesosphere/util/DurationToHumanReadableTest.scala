package mesosphere.util

import mesosphere.UnitTest

import scala.concurrent.duration._

class DurationToHumanReadableTest extends UnitTest {
  "Human Readable Durations" should {
    "should only show non zero values" in {
      val d = 1.day + 5.minutes + 10.milliseconds

      d.toHumanReadable should equal("1 days 5 minutes 10 milliseconds")
    }

    "should correctly convert Duration to human readable string" in {
      val d = 1.hour + 5.minutes + 10.seconds + 500.millis

      d.toHumanReadable should equal("1 hours 5 minutes 10 seconds 500 milliseconds")
    }

    "should show '0 milliseconds' if less than 1ms" in {
      val d = 999.microseconds

      d.toHumanReadable should equal("0 milliseconds")
    }
  }
}

