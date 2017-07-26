package mesosphere.marathon
package metrics

import akka.stream.scaladsl.{ Keep, Sink, Source }
import kamon.metric.instrument.CollectionContext
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually
import org.scalatest.exceptions.TestFailedException

import scala.Exception
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Try, Failure }

class MetricsTimerTest extends AkkaUnitTest with Eventually with Inside {

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
      implicit val clock = new SettableClock()
      val timer = HistogramTimer("timer")
      val promise = Promise[Int]()
      val graph = timer.forSource(Source.fromFuture(promise.future))
        .toMat(Sink.seq)(Keep.right)

      clock.plus(1.second)
      val sourceFuture = graph.run
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)
      clock.plus(1.second)
      promise.success(1)
      sourceFuture.futureValue should contain theSameElementsAs Seq(1)
      // histograms are not precise, so we check the range
      timer.histogram.collect(CollectionContext(10)).max shouldEqual (1000000000L +- 100000000L)
    }

    "measure a failed source (and propagate the exception)" in {
      implicit val clock = new SettableClock()
      val timer = HistogramTimer("timer")
      val promise = Promise[Int]()
      val graph = timer.forSource(Source.fromFuture(promise.future))
        .toMat(Sink.seq)(Keep.right)
      clock.plus(1.second)
      val sourceFuture = graph.run
      timer.histogram.collect(CollectionContext(10)).numberOfMeasurements should be(0L)
      clock.plus(1.second)
      val ex = new Exception("Very exception!")
      promise.failure(ex)
      inside(Try(sourceFuture.futureValue)) {
        case Failure(testFailEx: TestFailedException) =>
          testFailEx.cause shouldBe Some(ex)
      }

      timer.histogram.collect(CollectionContext(10)).max shouldEqual (1000000000L +- 100000000L)
    }
  }
}
