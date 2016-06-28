package mesosphere.marathon.util

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ Cancellable, Scheduler }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.util
import mesosphere.marathon.util.Retry.RetryOnFn

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class RetryTest extends AkkaUnitTest {
  implicit val scheduler = system.scheduler
  implicit val ctx = system.dispatcher

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
      delays += delay
      executor.execute(runnable)
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
        val ex = new Exception
        // scalastyle:off
        val result = util.Retry("failure", maxAttempts = 5, minDelay = 1.nano, maxDelay = 1.nano) {
          // scalastyle:on
          countCalls(counter)(Future.failed(ex))
        }.failed.futureValue
        result shouldBe a[TimeoutException]
        result.asInstanceOf[TimeoutException].cause should be(ex)
        // scalastyle:off
        counter.intValue() should equal(5)
        // scalastyle:on
      }
      "retry in strictly greater increments" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        // scalastyle:off
        util.Retry("failure", maxAttempts = 5, minDelay = 1.milli, maxDelay = 5.seconds) {
          // scalastyle:on
          Future.failed(new Exception)
        }.failed.futureValue
        // first call doesn't go through the scheduler
        // scalastyle:off
        delays.size should equal(4)
        // scalastyle:on
        delays.map(_.toNanos).sorted should equal(delays.map(_.toNanos))
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
        val ex = new Exception()
        val result = util.Retry.blocking("fail", retryOn = retryFn) {
          countCalls(counter)(throw ex)
        }.failed.futureValue
        counter.intValue() should equal(1)
        result should be(ex)
      }
      "retry if the exception is allowed" in {
        val counter = new AtomicInteger()
        val ex = new Exception
        // scalastyle:off
        val result = util.Retry.blocking("failure", maxAttempts = 5, minDelay = 1.nano, maxDelay = 1.nano) {
          // scalastyle:on
          countCalls(counter)(throw ex)
        }.failed.futureValue
        result shouldBe a[TimeoutException]
        result.asInstanceOf[TimeoutException].cause should be(ex)
        // scalastyle:off
        counter.intValue should equal(5)
        // scalastyle:on
      }
      "retry in strictly greater increments" in {
        val delays = mutable.Queue.empty[FiniteDuration]
        implicit val scheduler = trackingScheduler(delays)
        // scalastyle:off
        util.Retry.blocking("failure", maxAttempts = 5, minDelay = 1.milli, maxDelay = 5.seconds) {
          // scalastyle:on
          throw new Exception
        }.failed.futureValue

        // first call doesn't go through the scheduler
        // scalastyle:off
        delays.size should equal(4)
        // scalastyle:on
        delays.map(_.toNanos).sorted should equal(delays.map(_.toNanos))
      }
    }
  }
}
