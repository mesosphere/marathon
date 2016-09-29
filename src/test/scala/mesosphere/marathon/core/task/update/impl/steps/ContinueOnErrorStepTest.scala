package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ CaptureLogEvents, Mockito }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class ContinueOnErrorStepTest extends FunSuite with Matchers with GivenWhenThen with Mockito {
  test("name uses nested name") {
    object nested extends InstanceChangeHandler {
      override def name: String = "nested"
      override def process(update: InstanceChange): Future[Done] = {
        throw new scala.RuntimeException("not implemted")
      }
    }

    ContinueOnErrorStep(nested).name should equal ("continueOnError(nested)")
  }

  test("A successful step should not produce logging output") {
    val f = new Fixture
    Given("a nested step that is always successful")
    f.processUpdate(f.nested).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = f.processUpdate(ContinueOnErrorStep(f.nested)) // linter:ignore:UndesirableTypeInference
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    f.processUpdate(verify(f.nested, times(1)))
    And("not produce any logging output")
    logEvents.filter(_.getMessage.contains(s"[${f.dummyInstance.instanceId.idString}]")) should be (empty)
  }

  test("A failing step should log the error but proceed") {
    val f = new Fixture
    Given("a nested step that always fails")
    f.nested.name returns "nested"
    f.processUpdate(f.nested).asInstanceOf[Future[Unit]] returns Future.failed(new RuntimeException("error!"))

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = f.processUpdate(ContinueOnErrorStep(f.nested)) // linter:ignore:UndesirableTypeInference
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    f.processUpdate(verify(f.nested, times(1)))
    And("produce an error message in the log")
    logEvents.map(_.toString) should contain (
      s"[ERROR] while executing step nested for [${f.dummyInstance.instanceId.idString}], continue with other steps"
    )
  }

  class Fixture {
    private[this] val appId: PathId = PathId("/test")
    val dummyInstanceBuilder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val dummyInstance = dummyInstanceBuilder.getInstance()
    val nested = mock[InstanceChangeHandler]

    def processUpdate(step: InstanceChangeHandler): Future[_] = {
      step.process(TaskStatusUpdateTestHelper.running(dummyInstanceBuilder.getInstance()).wrapped)
    }
  }
}
