package mesosphere.marathon
package core.launchqueue.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, BackoffStrategy, Timestamp }

import scala.concurrent.duration._

class RateLimiterTest extends UnitTest {

  val clock = ConstantClock(Timestamp.now())

  private[this] val launchQueueConfig: LaunchQueueConfig = new LaunchQueueConfig {
    verify()
  }

  "RateLimiter" should {
    "addDelay" in {
      val limiter = new RateLimiter(launchQueueConfig, clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds))

      limiter.addDelay(app)

      limiter.getDeadline(app) should be(clock.now() + 10.seconds)
    }

    "addDelay for existing delay" in {
      val limiter = new RateLimiter(launchQueueConfig, clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds, factor = 2.0))

      limiter.addDelay(app) // linter:ignore:IdenticalStatements
      limiter.addDelay(app)

      limiter.getDeadline(app) should be(clock.now() + 20.seconds)
    }

    "resetDelaysOfViableTasks" in {
      val time_origin = clock.now()
      val limiter = new RateLimiter(launchQueueConfig, clock)
      val threshold = launchQueueConfig.minimumViableTaskExecutionDuration
      val viable = AppDefinition(id = "viable".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds))
      limiter.addDelay(viable)
      val notYetViable = AppDefinition(id = "notYetViable".toPath, backoffStrategy = BackoffStrategy(backoff = 20.seconds))
      limiter.addDelay(notYetViable)
      val stillWaiting = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = threshold + 20.seconds))
      limiter.addDelay(stillWaiting)

      clock += threshold + 11.seconds

      limiter.resetDelaysOfViableTasks()

      limiter.getDeadline(viable) should be(clock.now())
      limiter.getDeadline(notYetViable) should be(time_origin + 20.seconds)
      limiter.getDeadline(stillWaiting) should be(time_origin + threshold + 20.seconds)
    }

    "resetDelay" in {
      val limiter = new RateLimiter(launchQueueConfig, clock)
      val app = AppDefinition(id = "test".toPath, backoffStrategy = BackoffStrategy(backoff = 10.seconds))

      limiter.addDelay(app)

      limiter.resetDelay(app)

      limiter.getDeadline(app) should be(clock.now())
    }
  }
}
