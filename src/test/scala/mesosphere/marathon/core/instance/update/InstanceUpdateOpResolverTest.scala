package mesosphere.marathon
package core.instance.update

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect.Update
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.bus.{MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper}
import mesosphere.marathon.core.task.state.TaskConditionMapping
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp, VersionInfo}
import mesosphere.marathon.test.SettableClock
import org.apache.mesos
import org.scalatest.Inside

import scala.collection.immutable.Seq

/**
  * Some specialized tests for statusUpdate action resolving.
  *
  * More tests are in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]]
  */
class InstanceUpdateOpResolverTest extends UnitTest with Inside {

  "InstanceUpdateOpResolver" should {
    "ForceExpunge for an unknown task" in new Fixture {
      val stateChange = updateOpResolver.resolve(None, InstanceUpdateOperation.ForceExpunge(notExistingInstanceId))

      Then("result in a Noop")
      stateChange shouldBe a[InstanceUpdateEffect.Noop]
    }

    // this case is actually a little constructed, as the task will be loaded before and will fail if it doesn't exist
    "MesosUpdate for an unknown task" in new Fixture {
      Given("The instance is unknown")
      val instance = Option.empty[Instance]

      When("call taskTracker.task")
      val stateChange = updateOpResolver.resolve(instance, InstanceUpdateOperation.MesosUpdate(
        instance = existingInstance,
        mesosStatus = MesosTaskStatusTestHelper.running(),
        now = Timestamp(0)))

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]
    }

    for (
      reason <- TaskConditionMapping.Unreachable
    ) {
      s"TASK_LOST update with $reason indicating a TemporarilyUnreachable" in new Fixture {
        val instance = Some(existingInstance)

        When("we resolve the update")
        val operation = TaskStatusUpdateTestHelper.lost(reason, existingInstance).operation
        val effect = updateOpResolver.resolve(instance, operation)

        Then("result in an Update with the correct status")
        inside(effect) {
          case update: InstanceUpdateEffect.Update =>
            update.instance.isUnreachable shouldBe true
        }
      }
    }

    for (
      reason <- TaskConditionMapping.Gone
    ) {
      s"process TASK_LOST update with $reason indicating a task won't come" in new Fixture {
        val instance = Some(existingInstance)

        When("we resolve the update")
        val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(
          reason, existingInstance).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
        val stateChange = updateOpResolver.resolve(instance, stateOp)

        Then("result in an Update with the correct status")
        stateChange shouldBe a[InstanceUpdateEffect.Update]

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

        val events = eventsGenerator.events(
          expectedState, Some(updatedTask), stateOp.now, previousState = Some(existingInstance.state))
        stateChange shouldEqual InstanceUpdateEffect.Update(expectedState, instance, events)
      }

      s"expunge TASK_LOST update with $reason when instance is decommissioned" in new Fixture {
        val decommissionedInstance = Some(existingDecommissionedInstance)

        When("we resolve the update")
        val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(
          reason, decommissionedInstance.get).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
        val stateChange = updateOpResolver.resolve(decommissionedInstance, stateOp)

        Then("result in an expunge")
        stateChange shouldBe an[InstanceUpdateEffect.Expunge]
      }
    }

    for (
      reason <- TaskConditionMapping.Unreachable
    ) {
      s"expunge after TASK_LOST update on decommissionged instance with an unreachable $reason but a message saying that the task is unknown to the slave " in new Fixture {
        val instance = Some(existingDecommissionedInstance)

        When("we resolve the update")
        val message = "Reconciliation: Task is unknown to the slave"
        val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(
          reason, instance.get, Some(message)).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]
        val stateChange = updateOpResolver.resolve(instance, stateOp)

        Then("result in an expunge")
        stateChange shouldBe an[InstanceUpdateEffect.Expunge]
      }
    }

    "a subsequent TASK_LOST update with another reason" in new Fixture {
      val lostInstance = TestInstanceBuilder.newBuilder(appId).addTaskLost().getInstance()
      val instance = Some(lostInstance)
      val reason = mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED
      val taskId = Task.Id(lostInstance.instanceId)
      val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(
        state = mesos.Protos.TaskState.TASK_LOST,
        maybeReason = Some(reason),
        taskId = taskId
      )

      When("we resolve the update")
      val marathonTaskCondition = TaskCondition(mesosStatus)
      val stateOp = InstanceUpdateOperation.MesosUpdate(lostInstance, marathonTaskCondition, mesosStatus, clock.now())
      val stateChange = updateOpResolver.resolve(instance, stateOp)

      Then("result in an noop and not update the timestamp")
      stateChange shouldBe a[InstanceUpdateEffect.Noop]
    }

    "expunge after subsequent TASK_LOST update with a message saying that the task is unknown to the slave on decommissioned instance" in new Fixture {
      val instance = Some(unreachableDecommissionedInstance)

      When("we resolve the update")
      val reason = mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION
      val maybeMessage = Some("Reconciliation: Task is unknown to the slave")
      val stateOp: InstanceUpdateOperation.MesosUpdate = TaskStatusUpdateTestHelper.lost(
        reason, unreachableInstance, maybeMessage).operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]

      val stateChange = updateOpResolver.resolve(instance, stateOp)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "ReservationTimeout for an unknown instance" in new Fixture {
      When("we resolve the update")
      val stateChange = updateOpResolver.resolve(None, InstanceUpdateOperation.ReservationTimeout(notExistingInstanceId))

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]
    }

    "Processing a Schedule for an existing instanceId" in new Fixture {
      When("call taskTracker.task")
      val stateChange = updateOpResolver.resolve(Some(existingInstance), InstanceUpdateOperation.Schedule(existingInstance))

      Then("result in a Failure")
      stateChange shouldBe a[InstanceUpdateEffect.Failure]
    }

    "Processing a Reserve for an existing instanceId" in new Fixture {
      val stateChange = updateOpResolver.resolve(Some(reservedInstance), InstanceUpdateOperation.Reserve(reservedInstance))

      Then("result in an Update")
      stateChange shouldBe an[InstanceUpdateEffect.Update]
    }

    "Revert an update operation" in new Fixture {
      val stateChange = updateOpResolver.resolve(Some(reservedInstance), InstanceUpdateOperation.Revert(reservedInstance))

      Then("result in an Update")
      stateChange shouldEqual InstanceUpdateEffect.Update(reservedInstance, None, events = Nil)
    }

    // Mesos 1.1 task statuses specs. See https://mesosphere.atlassian.net/browse/DCOS-9941

    "expunge after TASK_FAILED update for running decommissioned" in new Fixture {
      val update = TaskStatusUpdateTestHelper.failed(existingDecommissionedInstance)
      val stateChange = updateOpResolver.resolve(Some(existingDecommissionedInstance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "expunge after TASK_GONE for a running decommissioned instance" in new Fixture {
      val update = TaskStatusUpdateTestHelper.gone(existingDecommissionedInstance)
      val stateChange = updateOpResolver.resolve(Some(existingDecommissionedInstance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "not expunge after TASK_GONE for instance with goal running" in new Fixture {
      val update = TaskStatusUpdateTestHelper.gone(existingInstance)
      val stateChange = updateOpResolver.resolve(Some(existingInstance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe a[InstanceUpdateEffect.Update]
    }

    "expunge after TASK_DROPPED update for a staging decommissioned instance" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.decommissioned().addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "expunge a Scheduled instance after it was decommissioned" in new Fixture {
      Given("a scheduled instance (no tasks)")
      val runSpec = AppDefinition(id = appId)
      val scheduled = Instance.scheduled(runSpec)

      When("it is decommissioned")
      val operation = InstanceUpdateOperation.ChangeGoal(scheduled.instanceId, Goal.Decommissioned)
      val stateChange = updateOpResolver.resolve(Some(scheduled), operation)

      Then("it should be expunged")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "Processing a TASK_DROPPED update for a starting task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.decommissioned().addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.dropped(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "Processing a TASK_UNREACHABLE update for a staging task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStaged().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an update")
      inside(stateChange) {
        case updateEffect: InstanceUpdateEffect.Update =>
          updateEffect.events should have size 2
      }
    }

    "Processing a TASK_UNREACHABLE update for a starting task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskStarting().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an update")
      stateChange shouldBe a[InstanceUpdateEffect.Update]
      inside(stateChange) {
        case updateEffect: InstanceUpdateEffect.Update =>
          updateEffect.events should have size 2
      }
    }

    "Processing a TASK_UNREACHABLE update for a running task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an update")
      inside(stateChange) {
        case updateEffect: InstanceUpdateEffect.Update =>
          updateEffect.events should have size 2
      }
    }

    "Processing a TASK_UNKNOWN update for an unreachable task" in new Fixture {
      val builder = TestInstanceBuilder.newBuilder(appId)
      val instance = builder.decommissioned().addTaskUnreachable().getInstance()
      val update = TaskStatusUpdateTestHelper.unknown(instance)
      val stateChange = updateOpResolver.resolve(Some(instance), update.operation)

      Then("result in an expunge")
      stateChange shouldBe an[InstanceUpdateEffect.Expunge]
    }

    "move instance to scheduled state when previously reserved" in new Fixture {
      val version = Timestamp(clock.instant())
      val runSpec = AppDefinition(id = PathId("foo"), versionInfo = VersionInfo.OnlyVersion(version))
      val stateChange = updateOpResolver.resolve(Some(reservedInstance), RescheduleReserved(reservedInstance, runSpec))

      inside(stateChange) {
        case update: Update =>
          update.instance.state.condition should be(Condition.Scheduled)
          update.instance.runSpecVersion should be(version)
      }
    }
  }

  class Fixture {
    val eventsGenerator = InstanceChangedEventsGenerator
    val clock = SettableClock.ofNow()
    val updateOpResolver = new InstanceUpdateOpResolver(clock)

    lazy val appId = PathId("/app")
    lazy val existingInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
    val existingDecommissionedInstance = existingInstance.copy(state = existingInstance.state.copy(goal = Goal.Decommissioned))
    lazy val existingTask: Task = existingInstance.appTask

    lazy val reservedInstance = TestInstanceBuilder.scheduledWithReservation(AppDefinition(appId)).copy(state = InstanceState(Condition.Killed, Timestamp.now(), None, healthy = None, Goal.Running))
    lazy val existingReservedTask: Task = reservedInstance.appTask

    lazy val reservedLaunchedInstance: Instance = TestInstanceBuilder.
      newBuilder(appId).addTaskResidentLaunched(Seq.empty).getInstance()

    lazy val notExistingInstanceId = Instance.Id.forRunSpec(appId)
    lazy val unreachableInstance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
    lazy val unreachableDecommissionedInstance = unreachableInstance.copy(state = unreachableInstance.state.copy(goal = Goal.Decommissioned))
  }
}