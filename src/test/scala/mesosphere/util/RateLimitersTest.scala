package mesosphere.util

import org.junit.Test
import org.junit.Assert._


class RateLimitersTest {
  @Test
  def testTryAcquire() {
    val rl = new RateLimiters()
    // Uses default rate limiter which allows 1 per second,
    // so 2nd call should fail
    assertTrue(rl.tryAcquire("foo"))
    assertFalse(rl.tryAcquire("foo"))
    Thread.sleep(1000)
    assertTrue(rl.tryAcquire("foo"))

    // Should take the new setting
    rl.setPermits("foo", 100.0)
    assertTrue(rl.tryAcquire("foo"))
    assertFalse(rl.tryAcquire("foo"))
    Thread.sleep(10)
    assertTrue(rl.tryAcquire("foo"))
  }
}
