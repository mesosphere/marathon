package mesosphere.util

import com.google.common.cache.{LoadingCache, CacheLoader, CacheBuilder}
import java.util.concurrent.Semaphore

object LockManager {
  def apply(): LoadingCache[String, Semaphore] = {
    CacheBuilder
      .newBuilder()
      .weakValues()
      .build[String, Semaphore](
        new CacheLoader[String, Semaphore] {
          override def load(key: String): Semaphore = new Semaphore(1)
        }
      )
  }
}
