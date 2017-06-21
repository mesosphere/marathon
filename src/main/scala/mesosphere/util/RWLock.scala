package mesosphere.util

import java.util.concurrent.locks.{ Lock, ReentrantReadWriteLock }

case class RWLock[T](private val value: T) {

  private[this] val lock = new ReentrantReadWriteLock

  private[this] def withLock[U](lock: Lock)(f: T => U): U = {
    scala.concurrent.blocking(lock.lock())
    try f(value)
    finally lock.unlock()
  }

  /**
    * Returns the result of evaluating the supplied function with the
    * underlying `T` value.
    *
    * The underlying read-lock is obtained before evaluating the
    * function, and released after it returns.
    *
    * It is *not* safe to use the `T` reference outside the
    * lifetime of the supplied closure.
    */
  def readLock[U](f: T => U): U =
    withLock(lock.readLock)(f)

  /**
    * Returns the result of evaluating the supplied function with the
    * underlying `T` value.
    *
    * The underlying write-lock is obtained before evaluating the
    * function, and released after it returns.
    *
    * It is *not* safe to use the `T` reference outside the
    * lifetime of the supplied closure.
    */
  def writeLock[U](f: T => U): U =
    withLock(lock.writeLock)(f)
}
