package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatusTestHelper, MesosTaskStatus, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.TaskStateOpResolver
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import org.apache.mesos
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.TaskTrackerImplTest]]
  */
class TaskStateOpResolverTest
    extends FunSuite with Mockito with GivenWhenThen with ScalaFutures with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("ForceExpunge results in NoChange if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.task(f.notExistingTaskId) returns Future.successful(None)

    When("A ForceExpunge is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.ForceExpunge(f.notExistingTaskId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.NoChange]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("LaunchOnReservation fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.task(f.notExistingTaskId) returns Future.successful(None)

    When("A LaunchOnReservation is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.LaunchOnReservation(
      taskId = f.notExistingTaskId,
      runSpecVersion = Timestamp(0),
      status = Task.Status(Timestamp(0)),
      hostPorts = Seq.empty)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
  test("MesosUpdate fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.task(f.existingTask.taskId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.MesosUpdate(
      task = f.existingTask,
      status = MarathonTaskStatusTestHelper.running,
      now = Timestamp(0))).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.existingTask.taskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  for (
    reason <- MesosTaskStatus.MightComeBack
  ) {
    test(s"a TASK_LOST update with $reason indicating a TemporarilyUnreachable task is mapped to an update") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.task(f.existingTask.taskId) returns Future.successful(Some(f.existingTask))

      When("A TASK_LOST update is received with a reason indicating it might come back")
      val stateOp = TaskStatusUpdateTestHelper.lost(reason, f.existingTask).wrapped.stateOp
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).task(f.existingTask.taskId)

      And("the result is an Update")
      stateChange shouldBe a[TaskStateChange.Update]

      And("the new state should have the correct status")
      val update: TaskStateChange.Update = stateChange.asInstanceOf[TaskStateChange.Update]
      update.newState.mesosStatus should not be empty
      update.newState.mesosStatus.get.getState shouldEqual mesos.Protos.TaskState.TASK_LOST

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  for (
    reason <- MesosTaskStatus.WontComeBack
  ) {
    test(s"a TASK_LOST update with $reason indicating a task won't come back is mapped to an expunge") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.task(f.existingTask.taskId) returns Future.successful(Some(f.existingTask))

      When("A TASK_LOST update is received with a reason indicating it won't come back")
      val stateOp: TaskStateOp.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingTask).wrapped.stateOp.asInstanceOf[TaskStateOp.MesosUpdate]
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).task(f.existingTask.taskId)

      And("the result is an Expunge")
      stateChange shouldBe a[TaskStateChange.Expunge]
      val expectedState = f.existingTask.copy(
        status = f.existingTask.status.copy(
          mesosStatus = stateOp.status.mesosStatus))
      stateChange shouldEqual TaskStateChange.Expunge(expectedState)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  test("a subsequent TASK_LOST update with another reason is mapped to a noop and will not update the timestamp") {
    val f = new Fixture

    Given("an existing lost task")
    f.taskTracker.task(f.existingLostTask.taskId) returns Future.successful(Some(f.existingLostTask))

    When("A subsequent TASK_LOST update is received")
    val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
    val stateOp: TaskStateOp.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingLostTask).wrapped.stateOp.asInstanceOf[TaskStateOp.MesosUpdate]
    val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.existingLostTask.taskId)

    And("the result is an noop")
    stateChange shouldBe a[TaskStateChange.NoChange]
    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("ReservationTimeout fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.task(f.notExistingTaskId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.ReservationTimeout(f.notExistingTaskId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.notExistingTaskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Launch fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.task(f.existingTask.taskId) returns Future.successful(Some(f.existingTask))

    When("A LaunchEphemeral is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.LaunchEphemeral(f.existingTask)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.existingTask.taskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Reserve fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.task(f.existingReservedTask.taskId) returns Future.successful(Some(f.existingReservedTask))

    When("A Reserve is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.Reserve(f.existingReservedTask)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).task(f.existingReservedTask.taskId)

    And("the result is a Failure")
    stateChange shouldBe a[TaskStateChange.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Revert does not query the state") {
    val f = new Fixture
    Given("a Revert stateOp")

    When("the stateOp is resolved")
    val stateChange = f.stateOpResolver.resolve(TaskStateOp.Revert(f.existingReservedTask)).futureValue

    And("the result is an Update")
    stateChange shouldEqual TaskStateChange.Update(f.existingReservedTask, None)

    And("The taskTracker is not queried at all")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val taskTracker = mock[TaskTracker]
    val stateOpResolver = new TaskStateOpResolver(taskTracker)

    val appId = PathId("/app")
    val existingTask = MarathonTestHelper.mininimalTask(appId)
    val existingReservedTask = MarathonTestHelper.residentReservedTask(appId)
    val notExistingTaskId = Task.Id.forRunSpec(appId)
    val existingLostTask = MarathonTestHelper.mininimalLostTask(appId)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
