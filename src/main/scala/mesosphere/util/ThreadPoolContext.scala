package mesosphere.util

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

object ThreadPoolContext {

  /**
    * This execution context is backed by a cached thread pool.
    * Use this context instead of the global execution context,
    * if you do blocking IO operations.
    */
  implicit lazy val context = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

}
