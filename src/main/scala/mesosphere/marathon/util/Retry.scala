package mesosphere.marathon.util

import akka.actor.Scheduler
import com.typesafe.config.Config

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking => blockingCall }
import scala.util.Random
import scala.util.control.NonFatal

case class RetryConfig(
  maxAttempts: Int = Retry.DefaultMaxAttempts,
  minDelay: Duration = Retry.DefaultMinDelay,
  maxDelay: Duration = Retry.DefaultMaxDelay)

object RetryConfig {
  def apply(config: Config): RetryConfig = {
    RetryConfig(
      config.int("max-attempts", default = Retry.DefaultMaxAttempts),
      config.duration("min-delay", default = Retry.DefaultMinDelay),
      config.duration("max-delay", default = Retry.DefaultMaxDelay)
    )
  }
}

/**
  * Functional transforms to retry methods using a form of Exponential Backoff with jitter.
  *
  * See also: https://www.awsarchitectureblog.com/2015/03/backoff.html
  */
object Retry {
  private[util] val random = new Random()
  val DefaultMaxAttempts = 5
  val DefaultMinDelay = 10.millis
  val DefaultMaxDelay = 1.second

  // akka cannot schedule tasks with a delay bigger than tickNanos * Int.MaxValue
  // which depends on the akka.scheduler.tick-duration config. By default this is
  // incredibly huge (years), so we restrict this to a sane value
  private val absoluteMaxDelay = 24.hours

  type RetryOnFn = Throwable => Boolean
  val defaultRetry: RetryOnFn = NonFatal(_)

  /** Returns a random value between two given boundaries. */
  private[util] def randomBetween(x: Long, y: Long): Long = {
    val min = math.min(x, y)
    val diff = math.abs(x - y) + 1
    math.abs(random.nextLong() % diff) + min
  }

  /** Computes a next delay based on the max duration and a given last duration. */
  private[util] def computeNextDelay(max: Duration, last: Duration): FiniteDuration = {
    randomBetween(
      last.toNanos,
      max.toNanos).nano
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
  def apply[T](
    name: String,
    maxAttempts: Int = DefaultMaxAttempts,
    minDelay: FiniteDuration = DefaultMinDelay,
    maxDelay: FiniteDuration = DefaultMaxDelay,
    retryOn: RetryOnFn = defaultRetry)(f: => Future[T])(implicit
    scheduler: Scheduler,
    ctx: ExecutionContext): Future[T] = {
    val promise = Promise[T]()

    require(
      maxDelay < absoluteMaxDelay,
      s"maxDelay of ${maxDelay.toSeconds} seconds is way too big")

    def retry(attempt: Int, lastDelay: FiniteDuration): Unit = {
      f.onComplete {
        case Success(result) =>
          promise.success(result)
        case Failure(e) if retryOn(e) =>
          if (attempt + 1 < maxAttempts) {
            val nextDelay = computeNextDelay(max = maxDelay, last = lastDelay)

            require(
              nextDelay < absoluteMaxDelay,
              s"nextDelay of ${nextDelay.toSeconds} seconds is too big, may not exceed ${absoluteMaxDelay.toSeconds}")

            scheduler.scheduleOnce(nextDelay)(retry(attempt + 1, nextDelay))
          } else {
            promise.failure(TimeoutException(s"$name failed after $maxAttempts attempt(s). Last error: ${e.getMessage}", e))
          }
        case Failure(e) =>
          promise.failure(e)
      }
    }
    retry(0, minDelay)
    promise.future
  }

  /**
    * Retry a blocking call
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
