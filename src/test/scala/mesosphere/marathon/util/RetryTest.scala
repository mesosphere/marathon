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
          Future.failed(new Exception)
        }.failed.futureValue
        // first call doesn't go through the scheduler
        delays.size should equal(4)
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
          throw new Exception
        }.failed.futureValue

        // first call doesn't go through the scheduler
        delays.size should equal(4)
        delays.map(_.toNanos).sorted should equal(delays.map(_.toNanos))
      }
    }
    "randomBetween" should {
      "always return a value between min and max" in {
        (1L to 100).foreach { i =>
          val max = i * 100
          val rand = Retry.randomBetween(i, max)
          rand should be <= max
        }
      }
      "return the same result for (x,y) and (y,x)" in {
        (1L to 100).foreach { i =>
          val max = i * 100
          Retry.random.setSeed(1L)
          val res1 = Retry.randomBetween(x = max, y = i)
          Retry.random.setSeed(1L)
          val res2 = Retry.randomBetween(x = i, y = max)
          res1 should equal (res2)
        }
      }
      "never return a delay bigger than maxDelay" in {
        // Should we rather add random test values (based on a random seed) here?
        val max = 5.seconds
        // hitting https://issues.scala-lang.org/browse/SI-8541 when trying to compare finiteDurations here, therefore .toSeconds
        (1 to 100).foreach { i =>
          val nextDelay = Retry.computeNextDelay(max, last = i.nanos)
          nextDelay.toSeconds should be < max.toSeconds
        }
      }
    }
  }
}
