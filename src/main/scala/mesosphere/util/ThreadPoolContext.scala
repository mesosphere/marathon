package mesosphere.util

import java.util.concurrent.Executors

import mesosphere.marathon.core.async.ContextPropagatingExecutionContextWrapper

import scala.concurrent.ExecutionContext

object ThreadPoolContext {

  private val numberOfThreads: Int = System.getProperty("numberOfIoThreads", "100").toInt

  /**
    * This execution context is backed by a cached thread pool.
    * Use this context instead of the global execution context,
    * if you do blocking IO operations.
    */
  implicit lazy val ioContext = ContextPropagatingExecutionContextWrapper(
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numberOfThreads))
  )

}
