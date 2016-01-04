package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.StatusUpdateActionResolver
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.TaskStatus
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }

import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.TaskTrackerImplTest]]
  */
class StatusUpdateActionResolverTest
    extends FunSuite with Mockito with GivenWhenThen with ScalaFutures with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("an update for a non-existing tasks is mapped to fail") {
    val f = new Fixture
    Given("a taskID without task")
    val appId = PathId("/app")
    val taskId = "task1"
    f.taskTracker.getTaskAsync(appId, taskId) returns Future.successful(None)
    And("a status update")
    val update = TaskStatus.getDefaultInstance

    When("resolve is called")
    val action = f.actionResolver.resolve(appId, taskId, update).futureValue

    Then("getTAskAsync is called")
    verify(f.taskTracker).getTaskAsync(appId, taskId)

    And("a fail action is returned")
    action.getClass should be(classOf[TaskOpProcessor.Action.Fail])
    action.asInstanceOf[TaskOpProcessor.Action.Fail].cause.getMessage should
      equal(s"task [$taskId] of app [$appId] does not exist")

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val taskTracker = mock[TaskTracker]
    val actionResolver = new StatusUpdateActionResolver(taskTracker)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
