package mesosphere.marathon.core.launchqueue.impl

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.test.MarathonActorSupport
import org.scalatest.Matchers

import scala.concurrent.duration._

class RateLimiterTest extends MarathonActorSupport with MarathonSpec with Matchers {
  val clock = ConstantClock(Timestamp.now())

  test("addDelay") {
    val limiter = new RateLimiter(clock)
    val app = AppDefinition(id = "test".toPath, backoff = 10.seconds)

    limiter.addDelay(app)

    limiter.getDelay(app) should be(clock.now() + 10.seconds)
  }

  test("addDelay for existing delay") {
    val limiter = new RateLimiter(clock)
    val app = AppDefinition(id = "test".toPath, backoff = 10.seconds, backoffFactor = 2.0)

    limiter.addDelay(app)
    limiter.addDelay(app)

    limiter.getDelay(app) should be(clock.now() + 20.seconds)
  }

  test("cleanupOverdueDelays") {
    val limiter = new RateLimiter(clock)
    val overdue = AppDefinition(id = "overdue".toPath, backoff = 10.seconds)
    limiter.addDelay(overdue)
    val stillWaiting = AppDefinition(id = "test".toPath, backoff = 20.seconds)
    limiter.addDelay(stillWaiting)

    clock += 11.seconds

    limiter.cleanUpOverdueDelays()

    limiter.getDelay(overdue) should be(clock.now())
    limiter.getDelay(stillWaiting) should be(clock.now() + 9.seconds)
  }

  test("resetDelay") {
    val limiter = new RateLimiter(clock)
    val app = AppDefinition(id = "test".toPath, backoff = 10.seconds)

    limiter.addDelay(app)

    limiter.resetDelay(app)

    limiter.getDelay(app) should be(clock.now())
  }

}
