package mesosphere.marathon
package core.base

/**
  * Simple value container which is used to help things know if Marathon is on it's way down
  */
trait LifecycleState {
  def isRunning: Boolean
}

object LifecycleState {
  object WatchingJVM extends LifecycleState {
    private[this] var running = true

    /* Note - each shutdown hook is run in it's own thread, so this won't have to wait until some other shutdownHook
     * finishes before the boolean can be set */
    sys.addShutdownHook {
      running = false
    }

    override def isRunning: Boolean = running
  }

  object Ignore extends LifecycleState {
    override val isRunning: Boolean = true
  }
}
