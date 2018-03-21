package mesosphere.marathon
package util

import akka.actor.Scheduler
import mesosphere.util.DurationToHumanReadable
import akka.pattern.after

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, blocking => blockingCall }

/**
  * Function transformations to make a method timeout after a given duration.
  */
object Timeout {
  /**
    * Timeout a blocking call
    * @param timeout The maximum duration the method may execute in
    * @param name Name of the operation
    * @param f The blocking call
    * @param scheduler The akka scheduler
    * @param ctx The execution context to execute 'f' in
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' or TimeoutException if it didn't complete in time.
    */
  def blocking[T](timeout: FiniteDuration, name: Option[String] = None)(f: => T)(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] =
    apply(timeout, name)(Future(blockingCall(f))(ctx))(scheduler, ctx)

  /**
    * Timeout a non-blocking call.
    * @param timeout The maximum duration the method may execute in
    * @param name Name of the operation
    * @param f The blocking call
    * @param scheduler The akka scheduler
    * @param ctx The execution context to execute 'f' in
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' or TimeoutException if it didn't complete
    */
  def apply[T](timeout: Duration, name: Option[String] = None)(f: => Future[T])(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] = {
    require(timeout != Duration.Zero)

    timeout match {
      case duration: FiniteDuration =>
        lazy val t = after(duration, scheduler)(Future.failed(new TimeoutException(s"${name.getOrElse("None")} timed out after ${timeout.toHumanReadable}")))
        Future.firstCompletedOf(Seq(f, t))
      case _ => f
    }
  }
}
