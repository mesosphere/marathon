package mesosphere.util

import mesosphere.marathon.state.PathId

import scala.collection.mutable
import com.google.common.util.concurrent.RateLimiter

/**
  * @author Tobi Knaup
  */

class RateLimiters(val defaultLimit: Double = 1.0) {

  private val rateLimiters = new mutable.HashMap[PathId, RateLimiter]() with mutable.SynchronizedMap[PathId, RateLimiter]

  def setPermits(id: PathId, permitsPerSecond: Double) {
    rateLimiters(id) = RateLimiter.create(permitsPerSecond)
  }

  def tryAcquire(id: PathId) = {
    rateLimiters.getOrElseUpdate(id, defaultRateLimiter()).tryAcquire
  }

  private def defaultRateLimiter() = {
    RateLimiter.create(defaultLimit)
  }
}
