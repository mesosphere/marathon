package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.state.{ NetworkInfo, TaskConditionMapping }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.impl.InstanceOpProcessorImpl.InstanceUpdateOpResolver
import mesosphere.marathon.core.task.{ Task, TaskCondition }
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
  * More tests are in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]]
  */
class InstanceUpdateOpResolverTest
    extends FunSuite with Mockito with GivenWhenThen with ScalaFutures with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("ForceExpunge results in NoChange if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingInstanceId) returns Future.successful(None)

    When("A ForceExpunge is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.ForceExpunge(f.notExistingInstanceId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingInstanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("LaunchOnReservation fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingInstanceId) returns Future.successful(None)

    When("A LaunchOnReservation is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.LaunchOnReservation(
      instanceId = f.notExistingInstanceId,
      runSpecVersion = Timestamp(0),
      timestamp = Timestamp(0),
      status = Task.Status(Timestamp(0), condition = Condition.Running, networkInfo = NetworkInfo.empty),
      hostPorts = Seq.empty)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingInstanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
  test("MesosUpdate fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.existingInstance.instanceId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.MesosUpdate(
      instance = f.existingInstance,
      mesosStatus = MesosTaskStatusTestHelper.running(),
      now = Timestamp(0))).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingInstance.instanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  for (
    reason <- TaskConditionMapping.Unreachable
  ) {
    test(s"a TASK_LOST update with $reason indicating a TemporarilyUnreachable task is mapped to an update") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))

      When("A TASK_LOST update is received with a reason indicating it might come back")
      val operation = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance).operation
      val effect = f.stateOpResolver.resolve(operation).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingInstance.instanceId)

      And("the result is an Update")
      effect shouldBe a[InstanceUpdateEffect.Update]

      And("the new state should have the correct status")
      val update: InstanceUpdateEffect.Update = effect.asInstanceOf[InstanceUpdateEffect.Update]
      update.instance.isUnreachableInactive should be (true)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  for (
    reason <- TaskConditionMapping.Gone
  ) {
    test(s"a TASK_LOST update with $reason indicating a task won't come back is mapped to an expunge") {
      val f = new Fixture

      Given("an existing instance")
      f.taskTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))

      When("A TASK_LOST update is received with a reason indicating it won't come back")
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingInstance.instanceId)

      And("the result is an Expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      // TODO(PODS): in order to be able to compare the instances, we need to tediously create a copy here
      // it should be verified elsewhere (in a unit test) that updating is done correctly both on task level
      // and on instance level, then it'd be enough here to check that the operation results in an
      // InstanceUpdateEffect.Expunge of the expected instanceId
      val updatedTask = f.existingTask.copy(status = f.existingTask.status.copy(
        mesosStatus = Some(stateOp.mesosStatus),
        condition = TaskCondition(stateOp.mesosStatus)
      ))
      val updatedTasksMap = f.existingInstance.tasksMap.updated(updatedTask.taskId, updatedTask)
      val expectedState = f.existingInstance.copy(
        state = f.existingInstance.state.copy(
          condition = TaskCondition(stateOp.mesosStatus),
          since = stateOp.now
        ),
        tasksMap = updatedTasksMap
      )

      val events = f.eventsGenerator.events(expectedState.state.condition, expectedState, Some(updatedTask), stateOp.now, expectedState.state.condition != f.existingInstance.state.condition)
      stateChange shouldEqual InstanceUpdateEffect.Expunge(expectedState, events)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  for (
    reason <- TaskConditionMapping.Unreachable
  ) {
    test(s"a TASK_LOST update with an unreachable $reason but a message saying that the task is unknown to the slave is mapped to an expunge") {
      val f = new Fixture

      Given("an existing task")
      f.taskTracker.instance(f.existingTask.taskId.instanceId) returns Future.successful(Some(f.existingInstance))

      When("A TASK_LOST update is received indicating the agent is unknown")
      val message = "Reconciliation: Task is unknown to the slave"
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance, Some(message)).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

      Then("taskTracker.task is called")
      verify(f.taskTracker).instance(f.existingTask.taskId.instanceId)

      And("the result is an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }
  }

  test("a subsequent TASK_LOST update with another reason is mapped to a noop and will not update the timestamp") {
    val f = new Fixture

    Given("an existing lost task")
    val lostInstance = TestInstanceBuilder.newBuilder(f.appId).addTaskLost().getInstance()
    f.taskTracker.instance(lostInstance.instanceId) returns Future.successful(Some(lostInstance))

    When("A subsequent TASK_LOST update is received")
    val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
    val taskId = Task.Id.forInstanceId(lostInstance.instanceId, None)
    val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(
      state = mesos.Protos.TaskState.TASK_LOST,
      maybeReason = Some(reason),
      taskId = taskId
    )
    val marathonTaskCondition = TaskCondition(mesosStatus)
    val stateOp = InstanceUpdateOperation.MesosUpdate(lostInstance, marathonTaskCondition, mesosStatus, f.clock.now())
    val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(lostInstance.instanceId)

    And("the result is an noop")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]
    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("a subsequent TASK_LOST update with a message saying that the task is unknown to the slave is mapped to an expunge") {
    val f = new Fixture

    Given("an existing lost task")
    f.taskTracker.instance(f.unreachableInstance.instanceId) returns Future.successful(Some(f.unreachableInstance))

    When("A subsequent TASK_LOST update is received indicating the agent is unknown")
    val reason = mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION
    val maybeMessage = Some("Reconciliation: Task is unknown to the slave")
    val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.unreachableInstance, maybeMessage).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
    val stateChange = f.stateOpResolver.resolve(stateOp).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.unreachableInstance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]
    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("ReservationTimeout fails if task does not exist") {
    val f = new Fixture
    Given("a non existing taskId")
    f.taskTracker.instance(f.notExistingInstanceId) returns Future.successful(None)

    When("A MesosUpdate is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.ReservationTimeout(f.notExistingInstanceId)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.notExistingInstanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Launch fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))

    When("A LaunchEphemeral is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.LaunchEphemeral(f.existingInstance)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingInstance.instanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Reserve fails if task already exists") {
    val f = new Fixture
    Given("an existing task")
    f.taskTracker.instance(f.existingReservedInstance.instanceId) returns Future.successful(Some(f.existingReservedInstance))

    When("A Reserve is scheduled with that taskId")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.Reserve(f.existingReservedInstance)).futureValue

    Then("taskTracker.task is called")
    verify(f.taskTracker).instance(f.existingReservedInstance.instanceId)

    And("the result is a Failure")
    stateChange shouldBe a[InstanceUpdateEffect.Failure]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Revert does not query the state") {
    val f = new Fixture
    Given("a Revert stateOp")

    When("the stateOp is resolved")
    val stateChange = f.stateOpResolver.resolve(InstanceUpdateOperation.Revert(f.existingReservedInstance)).futureValue

    And("the result is an Update")
    stateChange shouldEqual InstanceUpdateEffect.Update(f.existingReservedInstance, None, events = Nil)

    And("The taskTracker is not queried at all")
    f.verifyNoMoreInteractions()
  }

  // Mesos 1.1 task statuses specs. See https://mesosphere.atlassian.net/browse/DCOS-9941

  test("process TASK_FAILED update for running task") {
    val f = new Fixture

    Given("a running task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_FAILED update")
    val update = TaskStatusUpdateTestHelper.failed(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_GONE update for a running task") {
    val f = new Fixture

    Given("a running task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_GONE update")
    val update = TaskStatusUpdateTestHelper.gone(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_DROPPED update for a staging task") {
    val f = new Fixture

    Given("a staging task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStaged().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_DROPPED update")
    val update = TaskStatusUpdateTestHelper.dropped(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_DROPPED update for a starting task") {
    val f = new Fixture

    Given("a starting task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStarting().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_DROPPED update")
    val update = TaskStatusUpdateTestHelper.dropped(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_UNREACHABLE update for a staging task") {
    val f = new Fixture

    Given("a staging task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStaged().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_UNREACHABLE update")
    val update = TaskStatusUpdateTestHelper.unreachable(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an update")
    stateChange shouldBe a[InstanceUpdateEffect.Update]
    val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
    updateEffect.events should have size 2

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_UNREACHABLE update for a starting task") {
    val f = new Fixture

    Given("a starting task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStarting().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_UNREACHABLE update")
    val update = TaskStatusUpdateTestHelper.unreachable(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an update")
    stateChange shouldBe a[InstanceUpdateEffect.Update]
    val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
    updateEffect.events should have size 2

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_UNREACHABLE update for a running task") {
    val f = new Fixture

    Given("a running task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_UNREACHABLE update")
    val update = TaskStatusUpdateTestHelper.unreachable(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an update")
    stateChange shouldBe a[InstanceUpdateEffect.Update]
    val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
    updateEffect.events should have size 2

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process TASK_UNKNOWN update for an unreachable task") {
    val f = new Fixture

    Given("an unreachable task")
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskUnreachable().getInstance()
    f.taskTracker.instance(instance.instanceId) returns Future.successful(Some(instance))

    And("a TASK_UNKNOWN update")
    val update = TaskStatusUpdateTestHelper.unknown(instance)

    When("the update is resolved")
    val stateChange = f.stateOpResolver.resolve(update.operation).futureValue

    Then("the instance is fetched")
    verify(f.taskTracker).instance(instance.instanceId)

    And("the result is an expunge")
    stateChange shouldBe a[InstanceUpdateEffect.Expunge]

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    val eventsGenerator = InstanceChangedEventsGenerator
    val clock = ConstantClock()
    val taskTracker = mock[InstanceTracker]
    val stateOpResolver = new InstanceUpdateOpResolver(taskTracker, clock)

    val appId = PathId("/app")
    val existingInstanceBuilder = TestInstanceBuilder.newBuilder(appId).addTaskRunning()
    val existingTask: Task.LaunchedEphemeral = existingInstanceBuilder.pickFirstTask()
    val existingInstance: Instance = existingInstanceBuilder.getInstance()

    val reservedBuilder = TestInstanceBuilder.newBuilder(appId).addTaskReserved()
    val existingReservedInstance = reservedBuilder.getInstance()
    val existingReservedTask: Task.Reserved = reservedBuilder.pickFirstTask()
    val notExistingInstanceId = Instance.Id.forRunSpec(appId)
    val unreachableInstanceBuilder = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable()
    val unreachableInstance = unreachableInstanceBuilder.getInstance()

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
    }
  }
}
