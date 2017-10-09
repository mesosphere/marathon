package mesosphere.marathon
package util

import java.util.concurrent.atomic.AtomicBoolean

/**
  * This trait is meant to be used to mark a resource object as open or close.
  * A resource can be marked as open or close only once. Also, it can be closed
  * only when it is open.
  */
trait OpenableOnce {
  protected val opened = new AtomicBoolean(false)
  protected val wasEverOpened = new AtomicBoolean(false)

  /** Mark an object as open. */
  def markOpen(): Unit = {
    if (wasEverOpened.compareAndSet(false, true)) {
      opened.set(true)
    } else {
      throw new IllegalStateException("it was opened before")
    }
  }

  /** Mark an object as closed. */
  def markClosed(): Unit = {
    val wasOpened = opened.compareAndSet(true, false)
    if (!wasOpened) {
      throw new IllegalStateException("attempt to close while not being opened")
    }
  }

  /** This method returns true, if the object is open at the moment. */
  def isOpen: Boolean = opened.get()
}
