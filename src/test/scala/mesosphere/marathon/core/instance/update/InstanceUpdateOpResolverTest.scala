package mesosphere.marathon
package core.instance.update

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.state.{ NetworkInfoPlaceholder, TaskConditionMapping }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]]
  */
class InstanceUpdateOpResolverTest extends UnitTest with Inside {
  import scala.concurrent.ExecutionContext.Implicits.global

  "ForceExpunge for an unknown task" should {
    val f = new Fixture
    f.instanceTracker.instance(f.notExistingInstanceId) returns Future.successful(None)
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.ForceExpunge(f.notExistingInstanceId)).futureValue
    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.notExistingInstanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Noop] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "LaunchOnReservation for an unknown task" should {
    val f = new Fixture
    f.instanceTracker.instance(f.notExistingInstanceId) returns Future.successful(None)
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.LaunchOnReservation(
      instanceId = f.notExistingInstanceId,
      runSpecVersion = Timestamp(0),
      timestamp = Timestamp(0),
      status = Task.Status(Timestamp(0), condition = Condition.Running, networkInfo = NetworkInfoPlaceholder()),
      hostPorts = Seq.empty)).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.notExistingInstanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Failure] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
  "MesosUpdate for an unknown task" should {
    val f = new Fixture
    f.instanceTracker.instance(f.existingInstance.instanceId) returns Future.successful(None)
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.MesosUpdate(
      instance = f.existingInstance,
      mesosStatus = MesosTaskStatusTestHelper.running(),
      now = Timestamp(0))).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingInstance.instanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Failure] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  for (
    reason <- TaskConditionMapping.Unreachable
  ) {
    s"TASK_LOST update with $reason indicating a TemporarilyUnreachable" should {
      val f = new Fixture
      f.instanceTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))
      val operation = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance).operation
      val effect = f.updateOpResolver.resolve(operation).futureValue

      "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingInstance.instanceId) }
      "result in an Update" in { effect shouldBe a[InstanceUpdateEffect.Update] }
      "with the correct status" in {
        val update: InstanceUpdateEffect.Update = effect.asInstanceOf[InstanceUpdateEffect.Update]
        update.instance.isUnreachable should be (true)
      }
      "invoke no more interactions" in { f.verifyNoMoreInteractions() }
    }
  }

  for (
    reason <- TaskConditionMapping.Gone
  ) {
    s"TASK_LOST update with $reason indicating a task won't come" should {
      val f = new Fixture
      f.instanceTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.updateOpResolver.resolve(stateOp).futureValue

      "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingInstance.instanceId) }
      "result in an Expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }

      "with the correct status" in {
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

        val events = f.eventsGenerator.events(expectedState, Some(updatedTask), stateOp.now, previousCondition = Some(f.existingInstance.state.condition))
        stateChange shouldEqual InstanceUpdateEffect.Expunge(expectedState, events)
      }
      "invoke no more interactions" in { f.verifyNoMoreInteractions() }
    }
  }

  for (
    reason <- TaskConditionMapping.Unreachable
  ) {
    s"a TASK_LOST update with an unreachable $reason but a message saying that the task is unknown to the slave " should {
      val f = new Fixture
      f.instanceTracker.instance(f.existingTask.taskId.instanceId) returns Future.successful(Some(f.existingInstance))
      val message = "Reconciliation: Task is unknown to the slave"
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.existingInstance, Some(message)).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
      val stateChange = f.updateOpResolver.resolve(stateOp).futureValue

      "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingTask.taskId.instanceId) }
      "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
      "invoke no more interactions" in { f.verifyNoMoreInteractions() }
    }
  }

  "a subsequent TASK_LOST update with another reason" should {
    val f = new Fixture
    val lostInstance = TestInstanceBuilder.newBuilder(f.appId).addTaskLost().getInstance()
    f.instanceTracker.instance(lostInstance.instanceId) returns Future.successful(Some(lostInstance))
    val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
    val taskId = Task.Id.forInstanceId(lostInstance.instanceId, None)
    val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(
      state = mesos.Protos.TaskState.TASK_LOST,
      maybeReason = Some(reason),
      taskId = taskId
    )
    val marathonTaskCondition = TaskCondition(mesosStatus)
    val stateOp = InstanceUpdateOperation.MesosUpdate(lostInstance, marathonTaskCondition, mesosStatus, f.clock.now())
    val stateChange = f.updateOpResolver.resolve(stateOp).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(lostInstance.instanceId) }
    "result in an noop and not update the timestamp" in { stateChange shouldBe a[InstanceUpdateEffect.Noop] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "a subsequent TASK_LOST update with a message saying that the task is unknown to the slave" should {
    val f = new Fixture
    f.instanceTracker.instance(f.unreachableInstance.instanceId) returns Future.successful(Some(f.unreachableInstance))
    val reason = mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION
    val maybeMessage = Some("Reconciliation: Task is unknown to the slave")
    val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(reason, f.unreachableInstance, maybeMessage).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
    val stateChange = f.updateOpResolver.resolve(stateOp).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.unreachableInstance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "ReservationTimeout for an unknown instance" should {
    val f = new Fixture
    f.instanceTracker.instance(f.notExistingInstanceId) returns Future.successful(None)
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.ReservationTimeout(f.notExistingInstanceId)).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.notExistingInstanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Failure] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a Launch for an existing instanceId" should {
    val f = new Fixture
    f.instanceTracker.instance(f.existingInstance.instanceId) returns Future.successful(Some(f.existingInstance))
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.LaunchEphemeral(f.existingInstance)).futureValue
    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingInstance.instanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Failure] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a Reserve for an existing instanceId" should {
    val f = new Fixture
    f.instanceTracker.instance(f.existingReservedInstance.instanceId) returns Future.successful(Some(f.existingReservedInstance))
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.Reserve(f.existingReservedInstance)).futureValue

    "call taskTracker.task" in { verify(f.instanceTracker).instance(f.existingReservedInstance.instanceId) }
    "result in a Failure" in { stateChange shouldBe a[InstanceUpdateEffect.Failure] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Revert" should {
    val f = new Fixture
    val stateChange = f.updateOpResolver.resolve(InstanceUpdateOperation.Revert(f.existingReservedInstance)).futureValue

    "result in an Update" in { stateChange shouldEqual InstanceUpdateEffect.Update(f.existingReservedInstance, None, events = Nil) }
    "not query the taskTracker all" in { f.verifyNoMoreInteractions() }
  }

  // Mesos 1.1 task statuses specs. See https://mesosphere.atlassian.net/browse/DCOS-9941

  "Processing a TASK_FAILED update for running task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.failed(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_GONE update for a running task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.gone(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_DROPPED update for a staging task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStaged().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.dropped(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_DROPPED update for a starting task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStarting().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.dropped(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_UNREACHABLE update for a staging task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStaged().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.unreachable(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an update" in {
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2
    }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_UNREACHABLE update for a starting task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskStarting().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.unreachable(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an update" in {
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2
    }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_UNREACHABLE update for a running task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskRunning().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.unreachable(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an update" in {
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      val updateEffect = stateChange.asInstanceOf[InstanceUpdateEffect.Update]
      updateEffect.events should have size 2
    }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  "Processing a TASK_UNKNOWN update for an unreachable task" should {
    val f = new Fixture
    val builder = TestInstanceBuilder.newBuilder(f.appId)
    val instance = builder.addTaskUnreachable().getInstance()
    f.instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
    val update = TaskStatusUpdateTestHelper.unknown(instance)
    val stateChange = f.updateOpResolver.resolve(update.operation).futureValue

    "fetch the instance" in { verify(f.instanceTracker).instance(instance.instanceId) }
    "result in an expunge" in { stateChange shouldBe a[InstanceUpdateEffect.Expunge] }
    "invoke no more interactions" in { f.verifyNoMoreInteractions() }
  }

  class Fixture {
    val eventsGenerator = InstanceChangedEventsGenerator
    val clock = ConstantClock(Timestamp.now())
    val instanceTracker = mock[InstanceTracker]
    val updateOpResolver = new InstanceUpdateOpResolver(instanceTracker, clock)

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
      noMoreInteractions(instanceTracker)
    }
  }
}
