package mesosphere.marathon
package util

import java.time.{ Clock, Instant }
import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import mesosphere.marathon.core.async.RunContext
import mesosphere.util.{ CallerThreadExecutionContext, DurationToHumanReadable }

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking => blockingCall }

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
    ctx: ExecutionContext,
    clock: Clock = Clock.systemDefaultZone()): Future[T] =
    apply(timeout, name)(Future(blockingCall(f))(ctx))(scheduler, ctx, clock)

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
    ctx: ExecutionContext,
    clock: Clock = Clock.systemDefaultZone()): Future[T] = {
    require(timeout != Duration.Zero)

    if (timeout.isFinite()) {
      val promise = Promise[T]()
      val finiteTimeout = FiniteDuration(timeout.toNanos, TimeUnit.NANOSECONDS)
      val token = scheduler.scheduleOnce(finiteTimeout) {
        promise.tryFailure(new TimeoutException(s"$name timed out after ${timeout.toHumanReadable}"))
      }
      val result = RunContext.withContext(Instant.now(clock))(f)
      result.onComplete { res =>
        promise.tryComplete(res)
        token.cancel()
      }(CallerThreadExecutionContext.callerThreadExecutionContext)
      promise.future
    } else {
      f
    }
  }
}
