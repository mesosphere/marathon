package mesosphere.marathon.core.base

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.util.Timeout

import scala.concurrent.{ ExecutionContext, Future, _ }
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Add asyncExit method to Runtime.
  */
case class RichRuntime(runtime: Runtime) extends StrictLogging {

  /**
    * Exit this process in an async fashion.
    * First try exit regularly in the given timeout. If this does not exit in time, we halt the system.
    *
    * @param exitCode    the exit code to signal.
    * @param waitForExit the time to wait for a normal exit.
    * @return the Future of this operation.
    */
  def asyncExit(
    exitCode: Int = RichRuntime.FatalErrorSignal,
    waitForExit: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext): Future[Done] = {
    Timeout.unsafeBlocking(waitForExit)(sys.exit(exitCode)).recover {
      case _: TimeoutException => logger.error("Shutdown timeout")
      case NonFatal(t) => logger.error("Exception while committing suicide", t)
    }.failed.map { _ =>
      logger.info("Halting JVM")
      runtime.halt(exitCode)
      Done
    }
  }
}

object RichRuntime {
  val FatalErrorSignal = 137
}