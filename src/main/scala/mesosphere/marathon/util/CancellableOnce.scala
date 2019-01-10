package mesosphere.marathon
package util

import akka.actor.Cancellable
import java.util.concurrent.atomic.AtomicBoolean

/**
  * Cancellable implementation which calls the provided function once and only once
  */
class CancellableOnce(onCancel: () => Unit) extends Cancellable {
  private val cancelled = new AtomicBoolean(false)

  /**
    * Send a poison pill, once
    */
  def cancel(): Boolean = {
    if (cancelled.compareAndSet(false, true)) {
      onCancel()
      true
    } else {
      false
    }
  }

  def isCancelled = cancelled.get()
}

object CancellableOnce {
  def noop: Cancellable = new CancellableOnce(() => ())
}
