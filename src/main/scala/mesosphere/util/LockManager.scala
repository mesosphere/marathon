package mesosphere.util

import com.google.common.cache.{ LoadingCache, CacheLoader, CacheBuilder }
import java.util.concurrent.Semaphore

import scala.concurrent.{ ExecutionContext, Future }

/**
  * LockManager is used to serialize executions of futures.
  */
trait LockManager {

  /**
    * Execute the future with a lock on the given key.
    * @param key the key to use for locking.
    * @param future the future to execute, once the lock is available.
    * @param ec the execution context, the future gets executed on.
    * @tparam T the resulting type of the future.
    * @return A new future, that gets executed, once the lock is available.
    */
  def executeSequentially[T](key: String)(future: => Future[T])(implicit ec: ExecutionContext): Future[T]
}

object LockManager {

  def create(): LockManager = new LockManager {
    val locks = loadingCache[String]()
    override def executeSequentially[T](key: String)(future: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
      val lock = locks.get(key)
      scala.concurrent.blocking {
        lock.acquire()
      }
      val result = future
      result.onComplete { _ => lock.release() }
      result
    }
  }

  private[this] def loadingCache[A <: AnyRef](): LoadingCache[A, Semaphore] = {
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
