package mesosphere.marathon.core.task.update.impl.steps

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ Mockito, CaptureLogEvents }
import org.apache.mesos.Protos.{ TaskID, TaskStatus }
import org.scalatest.{ GivenWhenThen, Matchers, FunSuite }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class ContinueOnErrorStepTest extends FunSuite with Matchers with GivenWhenThen with Mockito {
  test("name uses nested name") {
    object nested extends TaskStatusUpdateStep {
      override def name: String = "nested"
      override def processUpdate(
        timestamp: Timestamp, appId: PathId, task: MarathonTask, mesosStatus: TaskStatus): Future[_] = ???
    }

    ContinueOnErrorStep(nested).name should equal ("continueOnError(nested)")
  }

  private[this] val timestamp: Timestamp = Timestamp(1)
  private[this] val appId: PathId = PathId("/test")
  private[this] val dummyTask: MarathonTask = MarathonTestHelper.dummyTask(appId)

  test("A successful step should not produce logging output") {
    def processUpdate(step: TaskStatusUpdateStep): Future[_] = {
      step.processUpdate(
        timestamp = timestamp,
        appId = appId,
        task = dummyTask,
        mesosStatus = TaskStatus.newBuilder().buildPartial()
      )
    }

    Given("a nested step that is always successful")
    val nested = mock[TaskStatusUpdateStep]
    processUpdate(nested).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = processUpdate(ContinueOnErrorStep(nested))
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    processUpdate(verify(nested, times(1)))
    And("not produce any logging output")
    logEvents should be (empty)
  }

  test("A failing step should log the error but proceed") {
    def processUpdate(step: TaskStatusUpdateStep): Future[_] = {
      step.processUpdate(
        timestamp, appId, task = dummyTask,
        mesosStatus = TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue("task")).buildPartial())
    }

    Given("a nested step that is always successful")
    val nested = mock[TaskStatusUpdateStep]
    nested.name returns "nested"
    processUpdate(nested).asInstanceOf[Future[Unit]] returns Future.failed(new RuntimeException("error!"))

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = processUpdate(ContinueOnErrorStep(nested))
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    processUpdate(verify(nested, times(1)))
    And("produce an error message in the log")
    logEvents.map(_.toString) should be (
      Vector("[ERROR] while executing step nested for [task], continue with other steps")
    )
  }
}
