package mesosphere.marathon
package metrics

import akka.stream.scaladsl.{ Sink, Source }
import kamon.metric.instrument.CollectionContext
import mesosphere.AkkaUnitTest

import scala.Exception
import scala.concurrent.Promise
import scala.util.Try

class MetricsTimerTest extends AkkaUnitTest {
  "Metrics Timers" should {
    "time crashing call" in {
      When("doing the call (but the future is delayed)")
      val timer = HistogramTimer("timer")

      val failure: RuntimeException = new scala.RuntimeException("failed")
      val attempt = Try(timer(throw failure))

      Then("we get the expected metric results")
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(1L)
      // TODO: check time but we need to mock the time first

      And("the original failure is preserved")
      attempt.failed.get should be(failure)
    }

    "time delayed successful future" in {
      When("doing the call (but the future is delayed)")
      val timer = HistogramTimer("timer")

      val promise = Promise[Unit]()
      val result = timer(promise.future)

      Then("the call has not yet been registered")
      Then("we get the expected metric results")
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)

      When("we fulfill the future")
      promise.success(())

      Then("we get the expected metric results")
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(1L)
      // TODO: check time but we need to mock the time first

      And("the original result is preserved")
      result.futureValue should be(())
    }

    "time delayed failed future" in {
      When("doing the call (but the future is delayed)")
      val timer = HistogramTimer("timer")

      val promise = Promise[Unit]()
      val result = timer(promise.future)

      Then("the call has not yet been registered")
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)

      When("we fulfill the future")
      val failure: RuntimeException = new scala.RuntimeException("simulated failure")
      promise.failure(failure)

      Then("we get the expected metric results")
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(1L)
      // TODO: check time but we need to mock the time first

      And("the failure should be preserved")
      result.failed.futureValue should be(failure)
    }

    "measure a successful source" in {
      val timer = HistogramTimer("timer")
      val promise = Promise[Int]()
      val sourceFuture = timer.forSource(Source.fromFuture(promise.future)).runWith(Sink.seq)
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)
      promise.success(1)
      sourceFuture.futureValue should contain theSameElementsAs Seq(1)
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(1L)
    }

    "measure a failed source" in {
      val timer = HistogramTimer("timer")
      val promise = Promise[Int]()
      val sourceFuture = timer.forSource(Source.fromFuture(promise.future)).runWith(Sink.seq)
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)
      val ex = new Exception("")
      promise.failure(ex)
      sourceFuture.futureValue
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(1L)
    }
  }
}