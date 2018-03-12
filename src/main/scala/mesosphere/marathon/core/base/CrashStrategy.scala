package mesosphere.marathon
package core.base

import akka.Done

import scala.concurrent.{ ExecutionContext, Future }

trait CrashStrategy {
  def crash(): Future[Done]
}

case object JvmExitsCrashStrategy extends CrashStrategy {
  override def crash(): Future[Done] = {
    Runtime.getRuntime.asyncExit()(ExecutionContext.Implicits.global)
  }
}
