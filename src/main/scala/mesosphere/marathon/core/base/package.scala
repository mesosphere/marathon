package mesosphere.marathon.core

import akka.Done
import scala.concurrent.{ ExecutionContext, Future, TimeoutException }
import scala.concurrent.duration._
import mesosphere.marathon.util.Timeout
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

package object base {

  /**
    * Add asyncExit method to Runtime.
    * @param runtime
    */
  implicit class RuntimeExtension(runtime: Runtime) {

    val FATAL_ERROR_SIGNAL = 137

    private[this] val log = LoggerFactory.getLogger(getClass.getName)

    /**
      * Exit this process in an async fashion.
      * First try exit regularly in the given timeout. If this does not exit in time, we halt the system.
      *
      * @param exitCode    the exit code to signal.
      * @param waitForExit the time to wait for a normal exit.
      * @return the Future of this operation.
      */
    def asyncExit(
      exitCode: Int = FATAL_ERROR_SIGNAL,
      waitForExit: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext): Future[Done] = {
      Timeout.unsafeBlocking(waitForExit)(sys.exit(exitCode)).recover {
        case _: TimeoutException => log.error("Shutdown timeout")
        case NonFatal(t) => log.error("Exception while committing suicide", t)
      }.failed.map { _ =>
        log.info("Halting JVM")
        runtime.halt(exitCode)
        Done
      }
    }
  }
}
