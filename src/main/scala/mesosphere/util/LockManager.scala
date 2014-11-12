package mesosphere.util

import com.google.common.cache.{ LoadingCache, CacheLoader, CacheBuilder }
import java.util.concurrent.Semaphore

object LockManager {
  def apply[A <: AnyRef](): LoadingCache[A, Semaphore] = {
    CacheBuilder
      .newBuilder()
      .weakValues()
      .build[A, Semaphore](
        new CacheLoader[A, Semaphore] {
          override def load(key: A): Semaphore = new Semaphore(1)
        }
      )
  }
}
