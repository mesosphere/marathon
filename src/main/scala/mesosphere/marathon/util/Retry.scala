package mesosphere.marathon.util

import akka.actor.Scheduler

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking => blockingCall }
import scala.util.control.NonFatal
import scala.util.{ Failure, Random, Success }

/**
  * Functional transforms to retry methods using a form of Exponential Backoff with jitter.
  *
  * See also: https://www.awsarchitectureblog.com/2015/03/backoff.html
  */
object Retry {
  type RetryOnFn = Throwable => Boolean
  val defaultRetry: RetryOnFn = NonFatal(_)

  private def randomBetween(min: Long, max: Long): Long = {
    math.abs(Random.nextLong() % (max - min + 1)) + min
  }

  /**
    * Retry a non-blocking call
    * @param maxAttempts The maximum number of attempts before failing
    * @param minDelay The minimum delay between invocations
    * @param maxDelay The maximum delay between invocations
    * @param retryOn A method that returns true for Throwables which should be retried
    * @param f The method to transform
    * @param scheduler The akka scheduler to execute on
    * @param ctx The execution context to run the method on
    * @tparam T The result type of 'f'
    * @return The result of 'f', TimeoutException if 'f' failed 'maxAttempts' with retry-able exceptions
    *         and the last exception that was thrown, or the last exception thrown if 'f' failed with a
    *         non-retry-able exception.
    */
  // scalastyle:off magic.number
  def apply[T](
    name: String,
    maxAttempts: Int = 5,
    minDelay: FiniteDuration = 10.millis,
    maxDelay: FiniteDuration = 1.second,
    retryOn: RetryOnFn = defaultRetry)(f: => Future[T])(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] = {
    // scalastyle:on
    val promise = Promise[T]()

    def retry(attempt: Int, lastDelay: FiniteDuration): Unit = {
      f.onComplete {
        case Success(result) =>
          promise.success(result)
        case Failure(e) if retryOn(e) =>
          if (attempt + 1 < maxAttempts) {
            val nextDelay = randomBetween(
              lastDelay.toNanos,
              math.min(
                maxDelay.toNanos,
                minDelay.toNanos * (2L << attempt))).nano
            scheduler.scheduleOnce(nextDelay)(retry(attempt + 1, nextDelay))
          } else {
            promise.failure(TimeoutException(s"$name failed after $maxAttempts. Last error: ${e.getMessage}", e))
          }
        case Failure(e) =>
          promise.failure(e)
      }
    }
    retry(0, Duration.Zero)
    promise.future
  }

  /**
    * Retry a non-blocking call
    * @param maxAttempts The maximum number of attempts before failing
    * @param minDelay The minimum delay between invocations
    * @param maxDelay The maximum delay between invocations
    * @param retryOn A method that returns true for Throwables which should be retried
    * @param f The method to transform
    * @param scheduler The akka scheduler to execute on
    * @param ctx The execution context to run the method on
    * @tparam T The result type of 'f'
    * @return The result of 'f', TimeoutException if 'f' failed 'maxAttempts' with retry-able exceptions
    *         and the last exception that was thrown, or the last exception thrown if 'f' failed with a
    *         non-retry-able exception.
    */
  def blocking[T](
    name: String,
    maxAttempts: Int = 5,
    minDelay: FiniteDuration = 10.millis,
    maxDelay: FiniteDuration = 1.second,
    retryOn: RetryOnFn = defaultRetry)(f: => T)(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] = {
    apply(name, maxAttempts, minDelay, maxDelay, retryOn)(Future(blockingCall(f)))
  }
}
