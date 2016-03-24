package mesosphere.util

import akka.actor.Terminated
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.integration.setup.WaitTestSupport
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.MarathonActorSupport
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class CapConcurrentExecutionsTest extends MarathonActorSupport with MarathonSpec with Matchers with GivenWhenThen
    with ScalaFutures {
  def capMetrics: CapConcurrentExecutionsMetrics = new CapConcurrentExecutionsMetrics(
    new Metrics(new MetricRegistry),
    classOf[CapConcurrentExecutionsTest]
  )

  test("submit successful futures after each other") {
    val serialize = CapConcurrentExecutions(capMetrics, system, "serialize1", maxParallel = 1, maxQueued = 10)
    try {
      val result1 = serialize(Future.successful(1)).futureValue
      result1 should be(1)
      val result2 = serialize(Future.successful(2)).futureValue
      result2 should be(2)
      val result3 = serialize(Future.successful(3)).futureValue
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("submit successful futures after a failure") {
    val serialize = CapConcurrentExecutions(capMetrics, system, "serialize2", maxParallel = 1, maxQueued = 10)
    try {
      serialize(Future.failed(new IllegalStateException())).failed.futureValue.getClass should be(classOf[IllegalStateException])
      val result2 = serialize(Future.successful(2)).futureValue
      result2 should be(2)
      val result3 = serialize(Future.successful(3)).futureValue
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("submit successful futures after a failure to return future") {
    val serialize = CapConcurrentExecutions(capMetrics, system, "serialize3", maxParallel = 1, maxQueued = 10)
    try {
      serialize(throw new IllegalStateException()).failed.futureValue.getClass should be(classOf[IllegalStateException])

      val result2 = serialize(Future.successful(2)).futureValue
      result2 should be(2)
      val result3 = serialize(Future.successful(3)).futureValue
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("concurrent executions are serialized if maxParallel has been reached") {
    val metrics = capMetrics
    val serialize = CapConcurrentExecutions(metrics, system, "serialize4", maxParallel = 2, maxQueued = 10)
    def submitPromise(): (Promise[Unit], Future[Unit]) = {
      val promise = Promise[Unit]()
      val result = serialize.apply(promise.future)
      (promise, result)
    }

    try {
      When("three promises are submitted but do not finish")
      val (promise1, result1) = submitPromise()
      val (promise2, result2) = submitPromise()
      val (promise3, result3) = submitPromise()

      Then("we have two promises executing and one queued")
      WaitTestSupport.waitUntil("messages have been processed", 1.second)(metrics.queued.getValue == 1)

      metrics.processing.getValue should be (2)
      metrics.queued.getValue should be (1)

      When("we finish one of executing the promises")
      promise2.success(())

      Then("we have two promises executing and none queued")
      WaitTestSupport.waitUntil("messages have been processed", 1.second)(metrics.queued.getValue == 0)

      metrics.processing.getValue should be (2)
      metrics.queued.getValue should be (0)

      And("we get the result of the finished promise")
      result2.futureValue should be(())

      When("we finish the other promises")
      promise1.success(())
      promise3.success(())

      Then("we eventually get all results")
      result1.futureValue should be(())
      result3.futureValue should be(())

      And("the gauges are zero again")
      WaitTestSupport.waitUntil("messages have been processed", 1.second)(metrics.processing.getValue == 0)
      metrics.queued.getValue should be(0)
      metrics.processing.getValue should be(0)

      And("all futures have been timed")
      metrics.processingTimer.invocationCount should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("queued executions are failed on stop, results of already executing futures are left untouched") {
    val metrics = capMetrics
    val serialize = CapConcurrentExecutions(metrics, system, "serialize5", maxParallel = 2, maxQueued = 10)
    def submitPromise(): (Promise[Unit], Future[Unit]) = {
      val promise = Promise[Unit]()
      val result = serialize.apply(promise.future)
      (promise, result)
    }

    try {
      When("three promises are submitted but do not finish")
      val (promise1, result1) = submitPromise()
      val (promise2, result2) = submitPromise()
      val (promise3, result3) = submitPromise()

      Then("we have two promises executing and one queued")
      WaitTestSupport.waitUntil("messages have been processed", 1.second)(metrics.queued.getValue == 1)

      metrics.processing.getValue should be (2)
      metrics.queued.getValue should be (1)

      When("the actor finishes now")
      watch(serialize.serializeExecutionActorRef)
      serialize.close()
      expectMsgClass(classOf[Terminated]).getActor should equal(serialize.serializeExecutionActorRef)

      Then("the queued futures is failed immediately")
      result3.failed.futureValue.getClass should be(classOf[IllegalStateException])

      And("the executing futures normally terminated")
      result1.isCompleted should be(false)
      result2.isCompleted should be(false)

      promise1.success(())
      promise2.success(())

      result1.futureValue should be(())
      result2.futureValue should be(())
    }
    finally {
      serialize.close()
    }
  }
}
