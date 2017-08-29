package mesosphere.marathon
package core.launchqueue.impl

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, BackoffStrategy }

import scala.concurrent.duration._

class RateLimiterTest extends UnitTest {

  val clock = SettableClock.ofNow()

  "RateLimiter" should {
    "addDelay" in {
      val limiter = new RateLimiter(clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds))

      limiter.addDelay(app)

      limiter.getDeadline(app) should be(clock.now() + 10.seconds)
    }

    "addDelay for existing delay" in {
      val limiter = new RateLimiter(clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds, factor = 2.0))

      limiter.addDelay(app) // linter:ignore:IdenticalStatements
      limiter.addDelay(app)

      limiter.getDeadline(app) should be(clock.now() + 20.seconds)
    }

    "resetDelaysOfViableTasks" in {
      val time_origin = clock.now()
      val limiter = new RateLimiter(clock)
      val threshold = 60.seconds

      val app1 = AppDefinition(
        id = "viable".toPath,
        backoffStrategy = BackoffStrategy(backoff = 10.seconds, maxLaunchDelay = threshold))
      limiter.addDelay(app1)

      val app2 = AppDefinition(
        id = "test".toPath,
        backoffStrategy = BackoffStrategy(backoff = 20.seconds, maxLaunchDelay = threshold + 10.seconds))
      limiter.addDelay(app2)

      // after advancing the clock by (threshold + 1), the existing delays
      // with maxLaunchDelay < (threshold + 1) should be gone
      clock += threshold + 1.seconds
      limiter.resetDelaysOfViableTasks()
      limiter.getDeadline(app1) should be(clock.now())
      limiter.getDeadline(app2) should be(time_origin + 20.seconds)
    }

    "resetDelay" in {
      val limiter = new RateLimiter(clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds))

      limiter.addDelay(app)
      limiter.resetDelay(app)

      limiter.getDeadline(app) should be(clock.now())
    }
  }
}
