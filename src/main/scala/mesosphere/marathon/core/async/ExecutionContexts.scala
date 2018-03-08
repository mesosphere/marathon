package mesosphere.marathon
package core.async

import java.util.concurrent.Executor

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }

object CallerThreadExecutionContext {
  val executor: Executor = (command: Runnable) => command.run()

  lazy val callerThreadExecutionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  def apply(): ExecutionContext = callerThreadExecutionContext
}

object ExecutionContexts {
  lazy val callerThread: ExecutionContext = CallerThreadExecutionContext()
}
