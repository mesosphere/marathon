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

    "cleanUpOverdueDelays" in {
      val time_origin = clock.now()
      val limiter = new RateLimiter(clock)
      val threshold = 60.seconds

      val appWithOverdueDelay = AppDefinition(
        id = "overdue".toPath,
        backoffStrategy = BackoffStrategy(backoff = 10.seconds, maxLaunchDelay = threshold))
      limiter.addDelay(appWithOverdueDelay)

      val appWithValidDelay = AppDefinition(
        id = "valid".toPath,
        backoffStrategy = BackoffStrategy(backoff = 20.seconds, maxLaunchDelay = threshold + 10.seconds))
      limiter.addDelay(appWithValidDelay)

      // after advancing the clock by (threshold + 1), the existing delays
      // with maxLaunchDelay < (threshold + 1) should be gone
      clock += threshold + 1.seconds
      limiter.cleanUpOverdueDelays()
      limiter.getDeadline(appWithOverdueDelay) should be(clock.now())
      limiter.getDeadline(appWithValidDelay) should be(time_origin + 20.seconds)
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
