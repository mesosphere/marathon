package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.TaskStateOpResolver
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.TaskTrackerImplTest]]
  */
// FIXME (3221): add missing tests
class TaskStateOpResolverTest
    extends FunSuite with Mockito with GivenWhenThen with ScalaFutures with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("an update for a non-existing tasks is mapped to fail") {
    val f = new Fixture
    Given("a taskID without task")
    val appId = PathId("/app")
    val taskId = Task.Id.forApp(appId)
    f.taskTracker.task(taskId) returns Future.successful(None)

    When("resolve is called")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.ForceExpunge(taskId)).futureValue

    Then("getTaskAsync is called")
    verify(f.taskTracker).task(taskId)

    And("the future fails")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val taskTracker = mock[TaskTracker]
    val stateOpResolver = new TaskStateOpResolver(taskTracker)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
