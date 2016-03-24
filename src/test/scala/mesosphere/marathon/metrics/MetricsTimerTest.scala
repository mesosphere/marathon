package mesosphere.marathon.metrics

import com.codahale.metrics.MetricRegistry
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Promise
import scala.util.Try

class MetricsTimerTest extends FunSuite with Matchers with GivenWhenThen with ScalaFutures {
  test("time crashing call") {
    When("doing the call (but the future is delayed)")
    val metrics = new Metrics(new MetricRegistry)
    val timer = metrics.timer("timer")
    val failure: RuntimeException = new scala.RuntimeException("failed")
    val attempt = Try(timer.timeFuture(throw failure))

    Then("we get the expected metric results")
    timer.timer.getCount should be(1)
    // TODO: check time but we need to mock the time first

    And("the original failure is preserved")
    attempt.failed.get should be(failure)
  }

  test("time delayed successful future") {
    When("doing the call (but the future is delayed)")
    val metrics = new Metrics(new MetricRegistry)
    val timer = metrics.timer("timer")
    val promise = Promise[Unit]()
    val result = timer.timeFuture(promise.future)

    Then("the call has not yet been registered")
    timer.timer.getCount should be(0)

    When("we fulfill the future")
    promise.success(())

    Then("we get the expected metric results")
    timer.timer.getCount should be(1)
    // TODO: check time but we need to mock the time first

    And("the original result is preserved")
    result.futureValue should be(())
  }

  test("time delayed failed future") {
    When("doing the call (but the future is delayed)")
    val metrics = new Metrics(new MetricRegistry)
    val timer = metrics.timer("timer")
    val promise = Promise[Unit]()
    val result = timer.timeFuture(promise.future)

    Then("the call has not yet been registered")
    timer.timer.getCount should be(0)

    When("we fulfill the future")
    val failure: RuntimeException = new scala.RuntimeException("simulated failure")
    promise.failure(failure)

    Then("we get the expected metric results")
    timer.timer.getCount should be(1)
    // TODO: check time but we need to mock the time first

    And("the failure should be preserved")
    result.failed.futureValue should be(failure)
  }
}
