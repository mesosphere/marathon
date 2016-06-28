package mesosphere.marathon.util

import java.util.{ Timer, TimerTask }

import akka.actor.Scheduler
import mesosphere.util.CallerThreadExecutionContext
import mesosphere.util.DurationToHumanReadable

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking => blockingCall }
import scala.util.Try

/**
  * Function transformations to make a method timeout after a given duration.
  */
object Timeout {
  /**
    * Timeout a blocking call
    * @param timeout The maximum duration the method may execute in
    * @param f The blocking call
    * @param scheduler The akka scheduler
    * @param ctx The execution context to execute 'f' in
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' or TimeoutException if it didn't complete in time.
    */
  def blocking[T](timeout: FiniteDuration)(f: => T)(implicit scheduler: Scheduler, ctx: ExecutionContext): Future[T] =
    apply(timeout)(Future(blockingCall(f)))(scheduler, ctx)

  /**
    * Timeout a blocking call _when Akka is not available_, e.g. when forcing a shutdown of the JVM.
    * Use with caution as it consumes a thread!
    * @param timeout The maximum duration the method may execute in
    * @param f The blocking call
    * @param ctx The execution context to execute 'f' on
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' of TimeoutException if it didn't complete in time.
    */
  def unsafeBlocking[T](timeout: FiniteDuration)(f: => T)(implicit ctx: ExecutionContext): Future[T] =
    unsafe(timeout)(Future(blockingCall(f)))

  /**
    * Timeout a non-blocking call _when Akka is not available_, e.g. when forcing a shutdown of the JVM.
    * Use with caution as it consumes a thread!
    * @param timeout The maximum duration the method may execute in
    * @param f The blocking call
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' or TimeoutException if it didn't complete in time.
    */
  def unsafe[T](timeout: FiniteDuration)(f: => Future[T]): Future[T] = {
    val promise = Promise[T]()
    val timer = new Timer()
    timer.schedule(new TimerTask {
      override def run(): Unit = promise.tryFailure(new TimeoutException(s"Timed out after ${timeout.toHumanReadable}"))
    }, timeout.toMillis)
    val result = f
    result.onComplete {
      case res: Try[T] =>
        promise.tryComplete(res)
        timer.cancel()
    }(CallerThreadExecutionContext.callerThreadExecutionContext)
    promise.future
  }

  /**
    * Timeout a non-blocking call.
    * @param timeout The maximum duration the method may execute in
    * @param f The blocking call
    * @param scheduler The akka scheduler
    * @param ctx The execution context to execute 'f' in
    * @tparam T The result type of 'f'
    * @return The eventual result of calling 'f' or TimeoutException if it didn't complete
    */
  def apply[T](timeout: FiniteDuration)(f: => Future[T])(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    val token = scheduler.scheduleOnce(timeout) {
      promise.tryFailure(new TimeoutException(s"Timed out after ${timeout.toHumanReadable}"))
    }
    val result = f
    result.onComplete {
      case res: Try[T] =>
        promise.tryComplete(res)
        token.cancel()
    }(CallerThreadExecutionContext.callerThreadExecutionContext)
    promise.future
  }
}
