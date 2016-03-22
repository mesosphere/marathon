package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.state.{ PathId, TaskRepository }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSpec, MarathonTestHelper }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResidentResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("Deleting an existing reserved task succeeds") {
    Given("A reserved resident task")
    val appId = PathId("/test")
    val task = MarathonTestHelper.residentReservedTask(appId)
    val expectedUpdate = task.copy(reservation = task.reservation.copy(
      state = Task.Reservation.State.Garbage(Some(Task.Reservation.Timeout(
        initiated = clock.now(), deadline = clock.now(), reason = Task.Reservation.Timeout.Reason.ReservationTimeout)))))
    val expectedUpdateProto = TaskSerializer.toProto(expectedUpdate)
    taskTracker.task(task.taskId) returns Future.successful(Some(task))
    taskRepository.store(expectedUpdateProto) returns Future.successful(expectedUpdateProto)

    When("DELETE v2/resident/<id> is called")
    val response = residentResource.delete(task.taskId.idString, auth.request)

    Then("Return status should be OK")
    response.getStatus should be(200)

    And("The returned task matched the expected state")
    response.getEntity shouldEqual Json.obj("task" -> task.taskId.idString).toString()

    And("The task was queried")
    verify(taskTracker).task(task.taskId)

    And("A timeout was set")
    verify(taskRepository).store(expectedUpdateProto)

    And("no more interactions")
    noMoreInteractions(taskTracker, taskRepository)
  }

  test("Deleting a non-existing task fails") {
    Given("A no reserved resident task")
    val taskId = Task.Id("invalid")
    taskTracker.task(taskId) returns Future.successful(None)

    When("DELETE v2/resident/<id> is called")
    Then("we get a 400")
    intercept[BadRequestException] { residentResource.delete(taskId.idString, auth.request) }

    And("The task was queried")
    verify(taskTracker).task(taskId)

    And("no more interactions")
    noMoreInteractions(taskTracker, taskRepository)
  }

  test("Deleting a launched task fails") {
    Given("A launched task")
    val appId = PathId("/test")
    val task = MarathonTestHelper.residentLaunchedTask(appId)
    taskTracker.task(task.taskId) returns Future.successful(Some(task))

    When("DELETE v2/resident/<id> is called")
    When("DELETE v2/resident/<id> is called")
    Then("we get a 400")
    intercept[BadRequestException] { residentResource.delete(task.taskId.idString, auth.request) }

    And("The task was queried")
    verify(taskTracker).task(task.taskId)

    And("no more interactions")
    noMoreInteractions(taskTracker, taskRepository)
  }

  var clock: ConstantClock = _
  var residentResource: ResidentResource = _
  var taskTracker: TaskTracker = _
  var taskRepository: TaskRepository = _
  var config: MarathonConf = _
  var auth: TestAuthFixture = _

  before {
    clock = ConstantClock()
    auth = new TestAuthFixture
    config = MarathonTestHelper.defaultConfig()
    taskTracker = mock[TaskTracker]
    taskRepository = mock[TaskRepository]
    residentResource = new ResidentResource(
      clock,
      taskTracker,
      taskRepository,
      config,
      auth.auth,
      auth.auth
    )
  }

}
