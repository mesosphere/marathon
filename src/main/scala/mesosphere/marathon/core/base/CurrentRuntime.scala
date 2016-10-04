package mesosphere.marathon.core.base

import akka.Done
import mesosphere.marathon.util.Timeout
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, TimeoutException }
import scala.language.implicitConversions
import scala.util.control.NonFatal


trait AsyncExitExtension {

  val FATAL_ERROR_SIGNAL = 137

  def asyncExit(
    exitCode: Int = FATAL_ERROR_SIGNAL,
    waitForExit: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext): Future[Done]
}


/**
  * Base for dependency injection of the implicit conversion.
  */
trait CurrentRuntime {

  /**
    * Extend runtime with an asyncExit call. We use an implicit def instead of an implicit class to enable dependency
    * injection.
    *
    * @param runtime
    * @return
    */
  implicit def extend(runtime: Runtime): AsyncExitExtension
}

object DefaultCurrentRuntime extends CurrentRuntime {

  /**
    * Default asyncCall implementation.
    * @param runtime
    */
  class RuntimeExtension(runtime: Runtime) extends AsyncExitExtension {

    private[this] val log = LoggerFactory.getLogger(getClass.getName)

    /**
      * Exit this process in an async fashion.
      * First try exit regularly in the given timeout. If this does not exit in time, we halt the system.
      *
      * @param exitCode    the exit code to signal.
      * @param waitForExit the time to wait for a normal exit.
      * @return the Future of this operation.
      */
    override def asyncExit(
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

  override implicit def extend(runtime: Runtime): AsyncExitExtension = new RuntimeExtension(runtime)

}
