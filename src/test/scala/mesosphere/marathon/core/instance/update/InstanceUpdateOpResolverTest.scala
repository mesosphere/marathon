package mesosphere.marathon
package core.instance.update

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.state.{ NetworkInfoPlaceholder, TaskConditionMapping }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]]
  */
class InstanceUpdateOpResolverTest extends UnitTest {

  import scala.concurrent.ExecutionContext.Implicits.global

  "InstanceUpdateOpResolver" should {
    "ForceExpunge for an unknown task" in new Fixture {
      instanceTracker.instance(notExistingInstanceId) returns Future.successful(None)
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.ForceExpunge(notExistingInstanceId)).futureValue
      When("call taskTracker.task")
      verify(instanceTracker).instance(notExistingInstanceId)

      Then("result in a Noop")
      stateChange shouldBe a[InstanceUpdateEffect.Noop]

      verifyNoMoreInteractions()
    }

    "LaunchOnReservation for an unknown task" in new Fixture {
      instanceTracker.instance(notExistingInstanceId) returns Future.successful(None)
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.LaunchOnReservation(
        instanceId = notExistingInstanceId,
        runSpecVersion = Timestamp(0),
        timestamp = Timestamp(0),
        status = Task.Status(Timestamp(0), condition = Condition.Running, networkInfo = NetworkInfoPlaceholder()),
        hostPorts = Seq.empty)).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(notExistingInstanceId)

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]

      verifyNoMoreInteractions()
    }

    // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
    "MesosUpdate for an unknown task" in new Fixture {
      instanceTracker.instance(existingInstance.instanceId) returns Future.successful(None)
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.MesosUpdate(
        instance = existingInstance,
        mesosStatus = MesosTaskStatusTestHelper.running(),
        now = Timestamp(0))).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(existingInstance.instanceId)

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]

      verifyNoMoreInteractions()
    }

    for (
      reason <- TaskConditionMapping.Unreachable
    ) {
      s"TASK_LOST update with $reason indicating a TemporarilyUnreachable" in new Fixture {
        val f = new Fixture
        instanceTracker.instance(existingInstance.instanceId) returns Future.successful(Some(existingInstance))
        val operation = TaskStatusUpdateTestHelper.lost(reason, existingInstance).operation
        val effect = updateOpResolver.resolve(operation).futureValue

        When("call taskTracker.task")
        verify(instanceTracker).instance(existingInstance.instanceId)

        Then("result in an Update with the correct status")
        effect shouldBe a[InstanceUpdateEffect.Update]

        val update: InstanceUpdateEffect.Update = effect.asInstanceOf[InstanceUpdateEffect.Update]
        update.instance.isUnreachable should be(true)

        verifyNoMoreInteractions()
      }
    }

    for (
      reason <- TaskConditionMapping.Gone
    ) {
      s"TASK_LOST update with $reason indicating a task won't come" in new Fixture {
        instanceTracker.instance(existingInstance.instanceId) returns Future.successful(Some(existingInstance))
        val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, existingInstance).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
        val stateChange = updateOpResolver.resolve(stateOp).futureValue

        When("call taskTracker.task")
        verify(instanceTracker).instance(existingInstance.instanceId)

        Then("result in an Expunge with the correct status")
        stateChange shouldBe a[InstanceUpdateEffect.Expunge]

        // TODO(PODS): in order to be able to compare the instances, we need to tediously create a copy here
        // it should be verified elsewhere (in a unit test) that updating is done correctly both on task level
        // and on instance level, then it'd be enough here to check that the operation results in an
        // InstanceUpdateEffect.Expunge of the expected instanceId
        val updatedTask = existingTask.copy(status = existingTask.status.copy(
          mesosStatus = Some(stateOp.mesosStatus),
          condition = TaskCondition(stateOp.mesosStatus)
        ))
        val updatedTasksMap = existingInstance.tasksMap.updated(updatedTask.taskId, updatedTask)
        val expectedState = existingInstance.copy(
          state = existingInstance.state.copy(
            condition = TaskCondition(stateOp.mesosStatus),
            since = stateOp.now
          ),
          tasksMap = updatedTasksMap
        )

        val events = eventsGenerator.events(expectedState, Some(updatedTask), stateOp.now, previousCondition = Some(existingInstance.state.condition))
        stateChange shouldEqual InstanceUpdateEffect.Expunge(expectedState, events)

        verifyNoMoreInteractions()
      }
    }

    for (
      reason <- TaskConditionMapping.Unreachable
    ) {
      s"a TASK_LOST update with an unreachable $reason but a message saying that the task is unknown to the slave " in new Fixture {
        instanceTracker.instance(existingTask.taskId.instanceId) returns Future.successful(Some(existingInstance))
        val message = "Reconciliation: Task is unknown to the slave"
        val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, existingInstance, Some(message)).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
        val stateChange = updateOpResolver.resolve(stateOp).futureValue

        When("call taskTracker.task")
        verify(instanceTracker).instance(existingTask.taskId.instanceId)

        Then("result in an expunge")
        stateChange shouldBe a[InstanceUpdateEffect.Expunge]

        verifyNoMoreInteractions()
      }
    }

    "a subsequent TASK_LOST update with another reason" in new Fixture {
      val lostInstance = TestInstanceBuilder.newBuilder(appId).addTaskLost().getInstance()
      instanceTracker.instance(lostInstance.instanceId) returns Future.successful(Some(lostInstance))
      val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
      val taskId = Task.Id.forInstanceId(lostInstance.instanceId, None)
      val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(
        state = mesos.Protos.TaskState.TASK_LOST,
        maybeReason = Some(reason),
        taskId = taskId
      )
      val marathonTaskCondition = TaskCondition(mesosStatus)
      val stateOp = InstanceUpdateOperation.MesosUpdate(lostInstance, marathonTaskCondition, mesosStatus, clock.now())
      val stateChange = updateOpResolver.resolve(stateOp).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(lostInstance.instanceId)

      Then("result in an noop and not update the timestamp")
      stateChange shouldBe a[InstanceUpdateEffect.Noop]

      verifyNoMoreInteractions()
    }

    "a subsequent TASK_LOST update with a message saying that the task is unknown to the slave" in new Fixture {
      instanceTracker.instance(unreachableInstance.instanceId) returns Future.successful(Some(unreachableInstance))
      val reason = mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION
      val maybeMessage = Some("Reconciliation: Task is unknown to the slave")
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, unreachableInstance, maybeMessage).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = updateOpResolver.resolve(stateOp).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(unreachableInstance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      verifyNoMoreInteractions()
    }

    "ReservationTimeout for an unknown instance" in new Fixture {
      instanceTracker.instance(notExistingInstanceId) returns Future.successful(None)
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.ReservationTimeout(notExistingInstanceId)).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(notExistingInstanceId)

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]

      verifyNoMoreInteractions()
    }

    "Processing a Launch for an existing instanceId" in new Fixture {
      instanceTracker.instance(existingInstance.instanceId) returns Future.successful(Some(existingInstance))
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.LaunchEphemeral(existingInstance)).futureValue
      When("call taskTracker.task")
      verify(instanceTracker).instance(existingInstance.instanceId)

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]

      verifyNoMoreInteractions()

    }

    "Processing a Reserve for an existing instanceId" in new Fixture {
      instanceTracker.instance(existingReservedInstance.instanceId) returns Future.successful(Some(existingReservedInstance))
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.Reserve(existingReservedInstance)).futureValue

      When("call taskTracker.task")
      verify(instanceTracker).instance(existingReservedInstance.instanceId)

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]

      verifyNoMoreInteractions()
    }

    "Revert" in new Fixture {
      val stateChange = updateOpResolver.resolve(InstanceUpdateOperation.Revert(existingReservedInstance)).futureValue

      When("result in an Update")
      stateChange shouldEqual InstanceUpdateEffect.Update(existingReservedInstance, None, events = Nil)

      Then("not query the taskTracker all")
      verifyNoMoreInteractions()
    }

    // Mesos 1.1 task statuses specs. See https://mesosphere.atlassian.net/browse/DCOS-9941

    "Processing a TASK_FAILED update for running task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskRunning().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.failed(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      verifyNoMoreInteractions()
    }

    "Processing a TASK_GONE update for a running task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskRunning().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.gone(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]
      verifyNoMoreInteractions()

    }

    "Processing a TASK_DROPPED update for a staging task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStaged().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      verifyNoMoreInteractions()
    }

    "Processing a TASK_DROPPED update for a starting task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStarting().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      verifyNoMoreInteractions()
    }

    "Processing a TASK_UNREACHABLE update for a staging task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStaged().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an update")
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2

      verifyNoMoreInteractions()
    }

    "Processing a TASK_UNREACHABLE update for a starting task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStarting().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an update")
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2

      verifyNoMoreInteractions()

    }

    "Processing a TASK_UNREACHABLE update for a running task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskRunning().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an update")
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2

      verifyNoMoreInteractions()
    }

    "Processing a TASK_UNKNOWN update for an unreachable task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskUnreachable().getInstance()
      instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
      val update = TaskStatusUpdateTestHelper.unknown(instance)
      val stateChange = updateOpResolver.resolve(update.operation).futureValue

      When("fetch the instance")
      verify(instanceTracker).instance(instance.instanceId)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Expunge]

      verifyNoMoreInteractions()
    }
  }

  class Fixture {
    val eventsGenerator = InstanceChangedEventsGenerator
    val clock = ConstantClock(Timestamp.now())
    val instanceTracker = mock[InstanceTracker]
    val updateOpResolver = new InstanceUpdateOpResolver(instanceTracker, clock)

    val appId = PathId("/app")
    val existingInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val existingTask: Task.LaunchedEphemeral = existingInstance.appTask

    val existingReservedInstance = TestInstanceBuilder.newBuilder(appId).addTaskReserved().getInstance()
    val existingReservedTask: Task.Reserved = existingReservedInstance.appTask
    val notExistingInstanceId = Instance.Id.forRunSpec(appId)
    val unreachableInstance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(instanceTracker)
    }
  }
}
