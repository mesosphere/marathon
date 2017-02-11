package mesosphere.marathon.core.base

import java.util.{ Timer, TimerTask }

import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, _ }

/**
  * Add asyncExit method to Runtime.
  */
case class RichRuntime(runtime: Runtime) {

  private lazy val logger = LoggerFactory.getLogger(getClass.getName)

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
    waitForExit: FiniteDuration = RichRuntime.DefaultExitDelay)(implicit ec: ExecutionContext): Future[Unit] = {
    val timer = new Timer()
    val promise = Promise[Unit]()
    timer.schedule(new TimerTask {
      override def run(): Unit = {
        logger.info("Halting JVM")
        promise.success(())
        // do nothing in tests: we can't guarantee we can block the exit
        if (!sys.props.get("java.class.path").exists(_.contains("test-classes"))) {
          Runtime.getRuntime.halt(exitCode)
        }
      }
    }, waitForExit.toMillis)
    Future(sys.exit(exitCode))
    promise.future
  }
}

object RichRuntime {
  val FatalErrorSignal = 137
  val DefaultExitDelay = 10.seconds
}
