package mesosphere.marathon
package core.base

import akka.Done
import mesosphere.marathon.core.async.ExecutionContexts

import scala.concurrent.Future

trait CrashStrategy {
  def crash(): Future[Done]
}

case object JvmExitsCrashStrategy extends CrashStrategy {
  override def crash(): Future[Done] = {
    Runtime.getRuntime.asyncExit()(ExecutionContexts.global)
  }
}
