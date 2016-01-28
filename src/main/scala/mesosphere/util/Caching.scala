package mesosphere.util

import java.util.concurrent.{ Callable, TimeUnit }

import com.google.common.cache.{ Cache, CacheBuilder }

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait Caching[T <: Object] {

  protected[this] def cacheExpiresAfter: FiniteDuration

  private[util] val cache: Cache[String, T] = CacheBuilder.newBuilder()
    .expireAfterWrite(cacheExpiresAfter.toMillis, TimeUnit.MILLISECONDS)
    .build()

  protected[this] def cached(key: String)(valueCall: => T) = {
    val valueLoader = new Callable[T] {
      override def call(): T = valueCall
    }

    cache.get(key, valueLoader)
  }
}
