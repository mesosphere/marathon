package mesosphere.marathon
package core.async

import org.slf4j.MDC

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import java.util.concurrent.Executor

/**
  * Mixin that enables org.slf4j.MDC and [[Context]] propagation across threads.
  */
trait ContextPropagatingExecutionContext extends ExecutionContext { self =>
  override def prepare(): ExecutionContext = new ExecutionContext {
    val mdcContext = Option(MDC.getCopyOfContextMap)
    val context = Context.copy // linter:ignore

    override def execute(runnable: Runnable): Unit = self.execute(new Runnable {
      def run(): Unit = {
        val oldMdc = Option(MDC.getCopyOfContextMap)

        try {
          // set the context for this thread
          mdcContext.fold(MDC.clear())(MDC.setContextMap)
          Context.withContext(context)(runnable.run())
        } finally {
          // restore it to the previous state
          oldMdc.fold(MDC.clear())(MDC.setContextMap)
        }
      }
    })

    override def reportFailure(cause: Throwable): Unit = self.reportFailure(cause)
  }
}

/**
  * Wrapper around another Execution Context that will Propagate MDC and Context.
  */
case class ContextPropagatingExecutionContextWrapper(wrapped: ExecutionContext)
    extends ExecutionContext with ContextPropagatingExecutionContext {
  override def execute(runnable: Runnable): Unit = wrapped.execute(runnable)

  override def reportFailure(cause: Throwable): Unit = wrapped.reportFailure(cause)
}

object CallerThreadExecutionContext {
  val executor: Executor = new Executor {
    override def execute(command: Runnable): Unit = command.run()
  }

  lazy val callerThreadExecutionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  def apply(): ExecutionContext = callerThreadExecutionContext
}

object ExecutionContexts {
  /** Prefer this context over the default scala one as it can propagate org.slf4j.MDC and [[Context]] */
  implicit lazy val global: ExecutionContext = ContextPropagatingExecutionContextWrapper(ExecutionContext.global)

  lazy val callerThread: ExecutionContext = CallerThreadExecutionContext()
}
