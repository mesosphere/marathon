package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

trait LifeCycledCloseableLike[T <: Closeable] extends Closeable {
  /**
    * The underlying closable object
    */
  def closeable: T

  /**
    * Registers a hook to be invoked _before_ closeable.close() is called.
    *
    * If it is called after the close() method is called, then an IllegalStateException is called.
    *
    * You should consider also calling removeBeforeClose, as appropriate, to avoid reference leaks.
    *
    * @param fn The function to call before this object's close() method is called
    */
  def beforeClose(fn: () => Unit): Unit

  /**
    * If it is called after the close() method is called, then it has no effect.
    *
    * @param fn The exact function instance to remove.
    */
  def removeBeforeClose(fn: () => Unit): Unit
}

class LifeCycledCloseable[T <: Closeable](val closeable: T) extends LifeCycledCloseableLike[T] with StrictLogging {
  private case class State(closed: Boolean, hooks: List[() => Unit])
  private val state = new AtomicReference(State(false, Nil))

  override def close(): Unit = {
    val State(_, hooks) = state.getAndSet(State(true, Nil))
    hooks.foreach { hook =>
      try hook()
      catch {
        case NonFatal(ex) =>
          logger.error(s"Exception thrown while calling close hook for ${closeable}", ex)
      }
    }
    closeable.close()
  }

  override def beforeClose(fn: () => Unit): Unit = state.updateAndGet { state =>
    if (state.closed)
      throw new IllegalStateException("already closed")
    else
      state.copy(hooks = fn :: state.hooks)
  }

  override def removeBeforeClose(fn: () => Unit): Unit = state.updateAndGet { state =>
    state.copy(hooks = state.hooks.filterNot(_ == fn))
  }
}
