package mesosphere.util

import mesosphere.marathon.MarathonSpec
import org.scalatest.Matchers

import scala.concurrent.duration._

class DurationToHumanReadableTest extends MarathonSpec with Matchers {

  test("should only show non zero values") {
    val d = 1.day + 5.minutes + 10.milliseconds

    d.toHumanReadable should equal("1 days 5 minutes 10 milliseconds")
  }

  test("should correctly convert Duration to human readable string") {
    val d = 1.hour + 5.minutes + 10.seconds + 500.millis

    d.toHumanReadable should equal("1 hours 5 minutes 10 seconds 500 milliseconds")
  }

  test("should show '0 milliseconds' if less than 1ms") {
    val d = 999.microseconds

    d.toHumanReadable should equal("0 milliseconds")
  }
}
