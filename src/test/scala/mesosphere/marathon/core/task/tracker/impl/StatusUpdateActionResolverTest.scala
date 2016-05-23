package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.bus.{ MesosTaskStatus, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessor.Action
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.StatusUpdateActionResolver
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.TaskStatus
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future
import scala.util.Random

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
    f.taskTracker.task(appId, taskId) returns Future.successful(None)
    And("a status update")
    val update = TaskStatus.getDefaultInstance

    When("resolve is called")
    val action = f.actionResolver.resolve(appId, taskId, update).futureValue

    Then("getTAskAsync is called")
    verify(f.taskTracker).task(appId, taskId)

    And("a fail action is returned")
    action.getClass should be(classOf[TaskOpProcessor.Action.Fail])
    action.asInstanceOf[TaskOpProcessor.Action.Fail].cause.getMessage should
      equal(s"task [$taskId] of app [$appId] does not exist")

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  for (
    update <- MesosTaskStatus.MightComeBack.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r))
  ) {
    test(s"a TASK_LOST update with ${update.reason} indicating a TemporarilyUnreachable Task is mapped to an update") {
      val f = new Fixture
      val task: MarathonTask = MarathonTestHelper.runningTask(update.wrapped.taskId.getValue)
      val status: TaskStatus = update.taskStatus
      val action = f.actionResolver.resolveForExistingTask(task, status)

      action shouldEqual Action.Update(task.toBuilder.setStatus(status).build())
    }
  }

  for (
    update <- MesosTaskStatus.WontComeBack.toSeq.map(r => TaskStatusUpdateTestHelper.lost(r))
  ) {
    test(s"a TASK_LOST update with ${update.reason} indicating a Task that won't come back is mapped to an expunge") {
      val f = new Fixture
      val task: MarathonTask = MarathonTestHelper.runningTask(update.wrapped.taskId.getValue)
      val status: TaskStatus = update.taskStatus
      val action = f.actionResolver.resolveForExistingTask(task, status)

      action shouldEqual Action.Expunge
    }
  }

  test("a subsequent TASK_LOST update with another reason is mapped to a noop and will not update the timestamp") {
    val f = new Fixture
    val update = TaskStatusUpdateTestHelper.lost(TaskStatus.Reason.REASON_SLAVE_DISCONNECTED)
    val task: MarathonTask = MarathonTestHelper.lostTask(update.wrapped.taskId.getValue, TaskStatus.Reason.REASON_RECONCILIATION)
    val status: TaskStatus = update.taskStatus

    val action = f.actionResolver.resolveForExistingTask(task, status)
    action shouldEqual Action.Noop
  }

  class Fixture {
    val taskTracker = mock[TaskTracker]
    val actionResolver = new StatusUpdateActionResolver(taskTracker)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
