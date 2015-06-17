package mesosphere.marathon.core.base

import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

trait ShutdownHooks {
  def onShutdown(block: => Unit): Unit

  def shutdown(): Unit
}

object ShutdownHooks {
  def apply(): ShutdownHooks = new DefaultShutdownHooks
}

private class DefaultShutdownHooks extends ShutdownHooks {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] var shutdownHooks = List.empty[() => Unit]

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      shutdown()
    }
  })

  override def onShutdown(block: => Unit): Unit = {
    shutdownHooks +:= { () => block }
  }

  override def shutdown(): Unit = {
    shutdownHooks.foreach { hook =>
      try hook()
      catch {
        case NonFatal(e) => log.error("while executing shutdown hook", e)
      }
    }
    shutdownHooks = Nil
  }
}
