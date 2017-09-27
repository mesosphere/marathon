package mesosphere.marathon
package util

import java.io.Closeable
import scala.util.Try

trait LifeCycledCloseableLike[T <: Closeable] extends Closeable {
  def closeable: T
  def beforeClose(fn: () => Unit): Unit
  def removeBeforeClose(fn: () => Unit): Unit
}

class LifeCycledCloseable[T <: Closeable](val closeable: T) extends LifeCycledCloseableLike[T] {
  @volatile private[this] var closed: Boolean = false
  @volatile private[this] var beforeCloseHooks: List[() => Unit] = Nil

  override def close(): Unit = {
    val hooks = synchronized {
      closed = true
      val oldHooks = beforeCloseHooks
      beforeCloseHooks = Nil
      oldHooks
    }
    hooks.foreach { hook =>
      Try(hook())
    }
    closeable.close()
    beforeCloseHooks = Nil
  }

  override def beforeClose(fn: () => Unit): Unit = synchronized {
    if (closed) throw new IllegalStateException("already closed")
    beforeCloseHooks = fn :: beforeCloseHooks
  }

  override def removeBeforeClose(fn: () => Unit): Unit = synchronized {
    beforeCloseHooks = beforeCloseHooks.filterNot(_ == fn)
  }
}
