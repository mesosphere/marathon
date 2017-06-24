package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.locks.{ ReentrantLock, ReentrantReadWriteLock }

class RichLock(val lock: ReentrantLock) extends AnyVal {
  def apply[T](f: => T): T = {
    lock.lock()
    try {
      f
    } finally {
      lock.unlock()
    }
  }
}

object RichLock {
  def apply(fair: Boolean = true): RichLock = new RichLock(new ReentrantLock(fair))
  def apply(lock: ReentrantLock): RichLock = new RichLock(lock)
}

class Lock[T](private val value: T, fair: Boolean = true) extends StrictLogging {
  private val lock = RichLock(fair)

  def apply[R](f: T => R): R = lock {
    f(value)
  }

  /**
    * Previously, this method was too smart and had a serious flaw. Given lock a and lock b, the following could
    * deadlock:
    *
    *     Future { a == b }
    *     Future { b == a }
    *
    * Throwing an exception seems safer than changing the behavior, as this way existing attempts to rely on this
    * behavior are much more obvious.
    *
    * After some time, we can remove the exception and use the default Java equals method (compare identity).
    */
  override def equals(o: Any): Boolean = {
    val ex = new RuntimeException(
      "Comparing two locked values was not safe and is no longer allowed.")
    logger.error("attempted usage of equals; remove!", ex)
    throw ex
  }

  override def hashCode(): Int = lock(value.hashCode())

  override def toString: String = lock {
    value.toString
  }
}

object Lock {
  def apply[T](value: T, fair: Boolean = true): Lock[T] = new Lock(value, fair)
}

class LockedVar[T](initialValue: T, fair: Boolean = true) {
  private[this] val lock = RichRwLock(fair)
  private[this] var item: T = initialValue

  def :=(f: => T): T = lock.write {
    item = f
    item
  }

  def update(f: T => T): T = lock.write {
    item = f(item)
    item
  }

  def get(): T = lock.read { item }
}

object LockedVar {
  def apply[T](initialValue: T, fair: Boolean = true): LockedVar[T] = new LockedVar(initialValue, fair)
}

class RichRwLock(val lock: ReentrantReadWriteLock) extends AnyVal {
  def read[T](f: => T): T = {
    lock.readLock.lock()
    try {
      f
    } finally {
      lock.readLock.unlock()
    }
  }

  def write[T](f: => T): T = {
    lock.writeLock.lock()
    try {
      f
    } finally {
      lock.writeLock.unlock()
    }
  }
}

object RichRwLock {
  def apply(fair: Boolean): RichRwLock = new RichRwLock(new ReentrantReadWriteLock(fair))
}

class RwLock[T](private val value: T, fair: Boolean) {
  private val lock = RichRwLock(fair)

  def read[R](f: T => R): R = lock.read {
    f(value)
  }

  def write[R](f: T => R): R = lock.write {
    f(value)
  }
}

object RwLock {
  def apply[T](value: T, fair: Boolean = true): RwLock[T] = new RwLock(value, fair)
}

