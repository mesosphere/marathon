package mesosphere.util

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.PathId._

class RateLimitersTest extends MarathonSpec {
  test("TryAcquire") {
    val rl = new RateLimiters()
    // Uses default rate limiter which allows 1 per second,
    // so 2nd call should fail
    val path = "foo".toPath
    assert(rl.tryAcquire(path))
    assert(!rl.tryAcquire(path))
    Thread.sleep(1000)
    assert(rl.tryAcquire(path))

    // Should take the new setting
    rl.setPermits(path, 100.0)
    assert(rl.tryAcquire(path))
    assert(!rl.tryAcquire(path))
    Thread.sleep(10)
    assert(rl.tryAcquire(path))
  }
}
