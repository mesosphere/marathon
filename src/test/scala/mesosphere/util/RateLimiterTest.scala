package mesosphere.util

import scala.concurrent.duration.{ FiniteDuration, SECONDS }

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.MarathonSpec

class RateLimiterTest extends MarathonSpec {

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

    val firstDuration = FiniteDuration(1, SECONDS)

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
    val app = AppDefinition(id = "myApp")

    rl addDelay app
    val delay = rl getDelay app
    assert(delay.hasTimeLeft)
  }

  test("GetDelay") {
    val rl = new RateLimiter()
    val app = AppDefinition(id = "myApp")

    assert(rl.getDelay(app).isOverdue)
    rl addDelay app
    assert(rl.getDelay(app).hasTimeLeft)
  }

  test("ResetDelay") {
    val rl = new RateLimiter()
    val app = AppDefinition(id = "myApp")

    rl addDelay app
    rl addDelay app
    rl addDelay app
    rl resetDelay app.id
    assert(rl.getDelay(app).isOverdue)
  }

}
