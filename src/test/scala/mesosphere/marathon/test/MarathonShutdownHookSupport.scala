package mesosphere.marathon.test

import mesosphere.marathon.core.base.ShutdownHooks
import org.scalatest.{ Suite, BeforeAndAfterEach }

/**
  * Creates shutdown hooks before each test and shuts it down after each test.
  */
trait MarathonShutdownHookSupport extends BeforeAndAfterEach { this: Suite =>

  var shutdownHooks: ShutdownHooks = _

  override protected def beforeEach(): Unit = {
    shutdownHooks = ShutdownHooks()
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    try super.afterEach()
    finally shutdownHooks.shutdown()
  }
}
