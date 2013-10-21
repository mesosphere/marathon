package mesosphere.util

import scala.collection.mutable
import com.google.common.util.concurrent.RateLimiter

/**
 * @author Tobi Knaup
 */

class RateLimiters(val defaultLimit: Double = 1.0) {

  private val rateLimiters = new mutable.HashMap[String, RateLimiter]()
    with mutable.SynchronizedMap[String, RateLimiter]

  def setPermits(name: String, permitsPerSecond: Double) {
    rateLimiters(name) = RateLimiter.create(permitsPerSecond)
  }

  def tryAcquire(name: String) = {
    rateLimiters.getOrElseUpdate(name, defaultRateLimiter()).tryAcquire
  }

  private def defaultRateLimiter() = {
    RateLimiter.create(defaultLimit)
  }
}
