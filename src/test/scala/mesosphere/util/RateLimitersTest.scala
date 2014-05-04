package mesosphere.util

import mesosphere.marathon.MarathonSpec


class RateLimitersTest extends MarathonSpec {
  test("TryAcquire") {
    val rl = new RateLimiters()
    // Uses default rate limiter which allows 1 per second,
    // so 2nd call should fail
    assert(rl.tryAcquire("foo"))
    assert(!rl.tryAcquire("foo"))
    Thread.sleep(1000)
    assert(rl.tryAcquire("foo"))

    // Should take the new setting
    rl.setPermits("foo", 100.0)
    assert(rl.tryAcquire("foo"))
    assert(!rl.tryAcquire("foo"))
    Thread.sleep(10)
    assert(rl.tryAcquire("foo"))
  }
}
