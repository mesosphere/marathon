package mesosphere.marathon
package test

import akka.Done
import mesosphere.marathon.core.base.CrashStrategy
import scala.concurrent.Future

class TestCrashStrategy extends CrashStrategy {
  @volatile var crashed: Boolean = false
  override def crash(): Future[Done] = {
    crashed = true
    Future.successful(Done)
  }
}
