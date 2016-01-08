package mesosphere.util

import java.util.concurrent.Executor

import scala.concurrent.ExecutionContext

/**
  * An ExecutionContext which executes everything on the caller thread.
  *
  * In some situations we can improve latency if a Future callback is
  * called directly on the thread completing the Future.
  *
  * Furthermore, it might be useful in testing.
  */
object CallerThreadExecutionContext {
  lazy val executor: Executor = new Executor {
    override def execute(command: Runnable): Unit = command.run()
  }

  implicit lazy val callerThreadExecutionContext = ExecutionContext.fromExecutor(executor)
}
