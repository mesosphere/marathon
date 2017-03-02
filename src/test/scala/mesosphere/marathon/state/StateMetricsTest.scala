package mesosphere.marathon
package state

import com.codahale.metrics.MetricRegistry
import mesosphere.UnitTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.StateMetrics.MetricTemplate

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.Try

class StateMetricsTest extends UnitTest {
  "StateMetrics" should {
    "time crashing read call" in {
      testCrashingCall(read = true)
    }
    "time crashing write call" in {
      testCrashingCall(read = false)
    }

    def testCrashingCall(read: Boolean): Unit = {
      When("doing the call (but the future is delayed)")
      val metrics = new TestableStateMetrics(0, 1.second.toNanos)
      val tested = if (read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val untested = if (!read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val timed = if (read) metrics.timedRead[Unit](_) else metrics.timedWrite[Unit](_)
      val failure: RuntimeException = new scala.RuntimeException("failed")
      val attempt = Try(timed(throw failure))

      Then("we get the expected metric results")
      metrics.metrics.registry.getMeters.get(untested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.errorMeterName).getCount should be(1)
      metrics.metrics.registry.getMeters.get(untested.requestsMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.requestsMeterName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(untested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getSnapshot.getMax should
        be(1.second.toMillis)

      And("the original failure is preserved")
      attempt.failed.get should be(failure)
    }

    "time delayed successful read future" in {
      testDelayedSuccesfulFuture(read = true)
    }
    "time delayed successful write future" in {
      testDelayedSuccesfulFuture(read = false)
    }

    def testDelayedSuccesfulFuture(read: Boolean): Unit = {
      When("doing the call (but the future is delayed)")
      val metrics = new TestableStateMetrics(0, 1.second.toNanos)
      val tested = if (read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val untested = if (!read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val timed: Future[Unit] => Future[Unit] =
        if (read) metrics.timedRead[Unit](_) else metrics.timedWrite[Unit](_)

      val promise = Promise[Unit]()
      val result = timed(promise.future)

      Then("we get the expected metric results (only invocation count)")
      metrics.metrics.registry.getMeters.get(untested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(untested.requestsMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.requestsMeterName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(untested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getSnapshot.getMax should be(0)

      When("we fulfill the future")
      promise.success(())

      Then("we get the expected metric results")
      metrics.metrics.registry.getMeters.get(untested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(untested.requestsMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.requestsMeterName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(untested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getSnapshot.getMax should
        be(1.second.toMillis)

      And("the original result is preserved")
      result.futureValue should be(())
    }

    "time delayed failed read future" in {
      testDelayedFailedFuture(read = true)
    }
    "time delayed failed write future" in {
      testDelayedFailedFuture(read = false)
    }

    def testDelayedFailedFuture(read: Boolean): Unit = {
      When("doing the call (but the future is delayed)")
      val metrics = new TestableStateMetrics(0, 1.second.toNanos)
      val tested = if (read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val untested = if (!read) metrics.readMetricsPublic else metrics.writeMetricsPublic
      val timed = if (read) metrics.timedRead[Unit](_) else metrics.timedWrite[Unit](_)
      val promise = Promise[Unit]()
      val result = timed(promise.future)

      Then("we get the expected metric results (only invocation count)")
      metrics.metrics.registry.getMeters.get(untested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(untested.requestsMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.requestsMeterName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(untested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getSnapshot.getMax should be(0)

      When("we fulfill the future")
      val failure: RuntimeException = new scala.RuntimeException("simulated failure")
      promise.failure(failure)

      Then("we get the expected metric results")
      metrics.metrics.registry.getMeters.get(untested.errorMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.errorMeterName).getCount should be(1)
      metrics.metrics.registry.getMeters.get(untested.requestsMeterName).getCount should be(0)
      metrics.metrics.registry.getMeters.get(tested.requestsMeterName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(untested.durationHistogramName).getCount should be(0)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getCount should be(1)
      metrics.metrics.registry.getHistograms.get(tested.durationHistogramName).getSnapshot.getMax should
        be(1.second.toMillis)

      And("the failure should be preserved")
      result.failed.futureValue should be(failure)
    }
  }
  private[this] class TestableStateMetrics(initial: Long*) extends StateMetrics {
    override lazy val metrics: Metrics = new Metrics(new MetricRegistry)

    val readMetricsPublic: MetricTemplate = readMetrics
    val writeMetricsPublic: MetricTemplate = writeMetrics

    override def timedRead[T](f: => Future[T]): Future[T] = super.timedRead(f)
    override def timedWrite[T](f: => Future[T]): Future[T] = super.timedWrite(f)

    var timeQueue = Queue[Long](initial: _*)

    override protected def nanoTime(): Long = {
      val (next, nextQueue) = timeQueue.dequeue
      timeQueue = nextQueue
      next
    }

  }
}
