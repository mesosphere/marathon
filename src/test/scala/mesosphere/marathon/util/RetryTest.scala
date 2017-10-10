package mesosphere.marathon
package util

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ Cancellable, Scheduler }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.util.Retry.RetryOnFn
import org.scalatest.prop.PropertyChecks

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class RetryTest extends AkkaUnitTest with PropertyChecks {
  val retryFn: RetryOnFn = {
    case _: IllegalArgumentException => true
    case _ => false
  }

  def countCalls[T](counter: AtomicInteger)(f: => T): T = {
    counter.incrementAndGet()
    f
  }

  def trackingScheduler(delays: mutable.Queue[FiniteDuration]): Scheduler = new Scheduler {
    override def schedule(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = ???
    override def maxFrequency: Double = ???
    override def scheduleOnce(
      delay: FiniteDuration,
      runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
      // we intentionally skip the call to Timeout
      if (delay != Retry.DefaultMaxDuration) {
        delays += delay
        executor.execute(runnable)
      }
      new Cancellable {
        override def isCancelled: Boolean = false
        override def cancel(): Boolean = false
      }
    }
  }

  "Retry" when {
    "async" should {
      "complete normally" in {
        val counter = new AtomicInteger()
        util.Retry("complete")(countCalls(counter)(Future.successful(1))).futureValue should equal(1)
        counter.intValue() should equal(1)
      }
      "fail if the exception is not in the allowed list" in {
        val ex = new Exception("expected")
        val counter = new AtomicInteger()
        val result = util.Retry("failure", retryOn = retryFn)(countCalls(counter)(Future.failed(ex))).failed.futureValue
        result should be(ex)
        counter.intValue() should equal(1)
      }
      "retry if the exception is allowed" in {
        val counter = new AtomicInteger()
        val ex = new Exception("")
        val result = util.Retry("failure", maxAttempts = 5, minDelay = 1.nano, maxDelay = 1.nano) {
          countCalls(counter)(Future.failed(ex))
        }.failed.futureValue
        result shouldBe a[TimeoutException]
        result.asInstanceOf[TimeoutException].cause should be(ex)
        counter.intValue() should equal(5)
      }
      "retry in strictly greater increments" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        util.Retry("failure", maxAttempts = 5, minDelay = 1.milli, maxDelay = 5.seconds) {
          Future.failed(new Exception(""))
        }.failed.futureValue
        // the first call doesn't go through the scheduler.
        delays.size should equal(4)
        delays.map(_.toNanos).sorted should equal(delays.map(_.toNanos))
        delays.toSet.size should equal(4) // never the same delay
      }
      "stop retrying if the maxDuration is reached" in {
        util.Retry("maxDuration", maxAttempts = Int.MaxValue, maxDelay = 1.nano, maxDuration = 2.nano) {
          Future.failed(new Exception(""))
        }.failed.futureValue
      }
      "never exceed maxDelay" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        util.Retry("failure", maxAttempts = 100, minDelay = 1.milli, maxDelay = 5.seconds) {
          Future.failed(new Exception(""))
        }.failed.futureValue

        delays.size should equal(99)
        delays.forall(_ <= 5.seconds) should be(true)
      }
    }
    "blocking" should {
      "complete normally" in {
        val counter = new AtomicInteger()
        util.Retry.blocking("complete") {
          countCalls(counter)(1)
        }.futureValue should equal(1)
        counter.intValue() should equal(1)
      }
      "fail if the exception is not in the allowed list" in {
        val counter = new AtomicInteger()
        val ex = new Exception("")
        val result = util.Retry.blocking("fail", retryOn = retryFn) {
          countCalls(counter)(throw ex)
        }.failed.futureValue
        counter.intValue() should equal(1)
        result should be(ex)
      }
      "retry if the exception is allowed" in {
        val counter = new AtomicInteger()
        val ex = new Exception("")
        val result = util.Retry.blocking("failure", maxAttempts = 5, minDelay = 1.nano, maxDelay = 1.nano) {
          countCalls(counter)(throw ex)
        }.failed.futureValue
        result shouldBe a[TimeoutException]
        result.asInstanceOf[TimeoutException].cause should be(ex)
        counter.intValue should equal(5)
      }
      "retry in strictly greater increments" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        util.Retry.blocking("failure", maxAttempts = 5, minDelay = 1.milli, maxDelay = 5.seconds) {
          throw new Exception("expected")
        }.failed.futureValue

        // the first call doesn't go through the scheduler.
        delays.size should equal(4)
        delays.map(_.toNanos).sorted should equal(delays.map(_.toNanos))
        delays.toSet.size should equal(4) // never the same delay
      }
      "stop retrying if the maxDuration is reached" in {
        util.Retry.blocking("maxDuration", maxAttempts = Int.MaxValue, maxDelay = 1.nano, maxDuration = 2.nano) {
          throw new Exception("")
        }.failed.futureValue
      }
      "never exceed maxDelay" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        util.Retry.blocking("failure", maxAttempts = 100, minDelay = 1.milli, maxDelay = 5.seconds) {
          throw new Exception("")
        }.failed.futureValue

        delays.size should equal(99)
        delays.forall(_ <= 5.seconds) should be(true)
      }
    }
    "randomBetween" should {
      "always return a value between min and max" in {
        implicit val generatorDrivenConfig = PropertyCheckConfiguration(minSuccessful = 100, maxDiscardedFactor = 100.0)
        forAll { (n: Long, m: Long) =>
          whenever(n > 0 && n < m) {
            Retry.randomBetween(n, m) should be <= m
          }
          whenever(n > 0) {
            Retry.randomBetween(n, Long.MaxValue) should be >= n
          }
        }
      }
    }
  }
}
