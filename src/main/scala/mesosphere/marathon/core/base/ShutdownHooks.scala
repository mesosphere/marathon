package mesosphere.marathon.core.base

import java.util.concurrent.atomic.AtomicBoolean

import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

trait ShutdownHooks {
  def onShutdown(block: => Unit): Unit

  def shutdown(): Unit

  def isShuttingDown: Boolean
}

object ShutdownHooks {
  def apply(): ShutdownHooks = new DefaultShutdownHooks
}

private[base] class BaseShutdownHooks extends ShutdownHooks {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] var shutdownHooks = List.empty[() => Unit]
  private[this] val shuttingDown = new AtomicBoolean(false)

  override def onShutdown(block: => Unit): Unit = {
    shutdownHooks +:= { () => block }
  }

  override def shutdown(): Unit = {
    shuttingDown.set(true)
    shutdownHooks.foreach { hook =>
      try hook()
      catch {
        case NonFatal(e) => log.error("while executing shutdown hook", e)
      }
    }
    shutdownHooks = Nil
  }

  override def isShuttingDown: Boolean = shuttingDown.get
}

/**
  * Extends BaseShutdownHooks by ensuring that the hooks are run when the VM shuts down.
  */
private class DefaultShutdownHooks extends BaseShutdownHooks {
  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      shutdown()
    }
  })
}
