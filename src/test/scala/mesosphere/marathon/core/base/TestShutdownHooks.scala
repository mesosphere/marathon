package mesosphere.marathon
package core.base

/**
  * ShutdownHooks which do not register callbacks for VM shutdown.
  */
object TestShutdownHooks {
  def apply(): ShutdownHooks = new TestShutdownHooks
}

private class TestShutdownHooks extends BaseShutdownHooks

