package mesosphere.util

import akka.actor.ActorSystem
import akka.testkit.TestKit
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import org.scalatest.Matchers

import scala.concurrent.duration._

class RateLimiterTest extends TestKit(ActorSystem("system")) with MarathonSpec with Matchers {
  test("addDelay") {
    val limiter = new RateLimiter
    val app = AppDefinition(id = "test".toPath, backoff = 10.seconds)

    limiter.addDelay(app)

    limiter.getDelay(app).isOverdue() should be(false)
  }

  test("resetDelay") {
    val limiter = new RateLimiter
    val app = AppDefinition(id = "test".toPath, backoff = 10.seconds)

    limiter.addDelay(app)

    limiter.resetDelay(app)

    limiter.getDelay(app).isOverdue() should be(true)
  }

  // regression test for #1005
  test("delay should decrease") {
    val limiter = new RateLimiter

    val app = AppDefinition(id = "test".toPath, backoff = 50.millis)

    limiter.addDelay(app)

    awaitCond(limiter.getDelay(app).isOverdue())
  }
}
