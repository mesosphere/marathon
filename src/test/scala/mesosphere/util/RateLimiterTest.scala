package mesosphere.util

import scala.concurrent.duration.{ FiniteDuration, SECONDS }

import org.apache.log4j.Logger

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.MarathonSpec

class RateLimiterTest extends MarathonSpec {

  private val log = Logger.getLogger(getClass.getName)

  /**
    * Returns `true` iff the supplied sequence is nondecreasing.
    *
    * @pre xs is finite.
    */
  def isMonotone[T <% Ordered[T]](xs: Iterable[T]): Boolean =
    xs.sliding(2, 1).foldLeft(true) {
      case (result, Seq(left, right)) => result && left <= right
    }

  test("Durations") {
    val rl = new RateLimiter()

    val firstDuration = FiniteDuration(2, SECONDS)

    val ds = rl.durations(
      initial = firstDuration,
      factor = 2.0,
      limit = firstDuration * 16
    )

    val dsView = ds take 100

    assert(isMonotone(dsView.toIterable))
  }

  test("AddDelay") {
    val rl = new RateLimiter()
    val app = AppDefinition(
      id = "my-app",
      launchDelay = FiniteDuration(2, SECONDS),
      launchDelayFactor = 2.0
    )

    rl addDelay app
    val delay = rl getDelay app
    assert(delay.hasTimeLeft)
  }

  test("GetDelay") {
    val rl = new RateLimiter()
    val app = AppDefinition(
      id = "my-app",
      launchDelay = FiniteDuration(2, SECONDS),
      launchDelayFactor = 2.0
    )

    assert(rl.getDelay(app).isOverdue)
    rl addDelay app
    assert(rl.getDelay(app).hasTimeLeft)
  }

  test("ResetDelay") {
    val rl = new RateLimiter()
    val app = AppDefinition(
      id = "my-app",
      launchDelay = FiniteDuration(2, SECONDS),
      launchDelayFactor = 2.0
    )

    rl addDelay app
    rl addDelay app
    rl addDelay app
    rl resetDelay app.id
    assert(rl.getDelay(app).isOverdue)
  }

}
