package mesosphere.marathon
package util

import akka.actor.{ ActorRef, Cancellable, PoisonPill }
import java.util.concurrent.atomic.AtomicBoolean

/**
  * Cancellable implementation which sends the provided actorRef at most one PoisonPill.
  */
class ActorCancellable(actorRef: ActorRef) extends Cancellable {
  private var cancelled = new AtomicBoolean(false)

  /**
    * Send a poison pill, once
    */
  def cancel(): Boolean = {
    if (cancelled.compareAndSet(false, true)) {
      actorRef ! PoisonPill
      true
    } else {
      false
    }
  }

  def isCancelled = cancelled.get()
}
