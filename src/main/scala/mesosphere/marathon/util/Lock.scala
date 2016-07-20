package mesosphere.marathon.util

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

class Lock[T](private val value: T, fair: Boolean = true) {
  private val lock = RichLock(fair)

  def apply[R](f: T => R): R = lock {
    f(value)
  }

  override def equals(o: Any): Boolean = o match {
    case r: Lock[T] => lock {
      r.lock {
        value.equals(r.value)
      }
    }
    case r: T @unchecked => lock {
      value.equals(r)
    }
    case _ => false
  }

  override def hashCode(): Int = lock(value.hashCode())

  override def toString: String = lock {
    value.toString
  }
}

object Lock {
  def apply[T](value: T, fair: Boolean = true): Lock[T] = new Lock(value, fair)
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

