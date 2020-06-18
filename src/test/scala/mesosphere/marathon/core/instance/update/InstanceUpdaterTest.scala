package mesosphere.marathon
package core.instance.update

import java.util.UUID

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{InstanceChanged, MesosStatusUpdateEvent}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, InstanceState, PrefixInstance}
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect.Expunge
import mesosphere.marathon.core.instance.Reservation.State.Suspended
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect.Update
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation, TestInstanceBuilder}
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.bus.{MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper}
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import org.apache.mesos.Protos.TaskState.TASK_UNREACHABLE
import org.scalatest.Inside

import scala.concurrent.duration._

class InstanceUpdaterTest extends UnitTest with Inside {

  "A staged instance" when {
    "processing a TASK_RUNNING update for a staged instance" should {
      val f = new Fixture

      Given(" a staged instance with a staged task")
      val mesosTaskStatus = MesosTaskStatusTestHelper.staging(f.taskId)
      val stagedStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Staging, mesosStatus = Some(mesosTaskStatus))
      val stagedTask = f.task.copy(status = stagedStatus)
      val stagedState = f.instanceState.copy(condition = Condition.Staging)
      val stagedInstance = f.instance.copy(tasksMap = Map(f.taskId -> stagedTask), state = stagedState)

      And("the instance receives a TASK_RUNNING mesos update")
      val operation = InstanceUpdateOperation.MesosUpdate(stagedInstance, f.mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(stagedInstance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
    }
  }

  "A Running instance" when {
    "processing an Unreachable update" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in an update effect" in {
        result shouldBe a[InstanceUpdateEffect.Update]
      }
      "add an instance changed event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(1) shouldBe a[InstanceChanged]
      }
      "add a task event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(0) match {
          case MesosStatusUpdateEvent(_, _, taskStatus, _, _, _, _, _, _, _, _) => taskStatus should be(TASK_UNREACHABLE)
          case _ => fail("Event did not match MesosStatusUpdateEvent")
        }
      }
    }

    "should expunge instances immediately when the instance expunge" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))
      val unreachableInactiveAfter = f.instance.unreachableStrategy.asInstanceOf[UnreachableEnabled].inactiveAfter
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())

      // Forward time to expire unreachable status
      f.clock.advanceBy(unreachableInactiveAfter)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "become unreachable inactive" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(1) match {
          case InstanceChanged(instanceId, _, _, condition, _) =>
            instanceId should be(f.instance.instanceId)
            condition should be(Condition.UnreachableInactive)
          case _ => fail("Event did not match InstanceChanged")
        }
      }
      "add a task event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(0) match {
          case MesosStatusUpdateEvent(_, _, taskStatus, _, _, _, _, _, _, _, _) =>
            taskStatus should be(TASK_UNREACHABLE)
          case _ => fail("Event did not match MesosStatusUpdateEvent")
        }
      }
    }

    "processing an expired unreachable, but not yet reaching the expunge threshold" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))
      val unreachableInactiveAfter = f.instance.unreachableStrategy.asInstanceOf[UnreachableEnabled].inactiveAfter
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())

      // Forward time to expire unreachable status
      f.clock.advanceBy(unreachableInactiveAfter)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "become unreachable inactive" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(1) match {
          case InstanceChanged(instanceId, _, _, condition, _) =>
            instanceId should be(f.instance.instanceId)
            condition should be(Condition.UnreachableInactive)
          case _ => fail("Event did not match InstanceChanged")
        }
      }
      "add a task event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(0) match {
          case MesosStatusUpdateEvent(_, _, taskStatus, _, _, _, _, _, _, _, _) =>
            taskStatus should be(TASK_UNREACHABLE)
          case _ => fail("Event did not match MesosStatusUpdateEvent")
        }
      }
    }

    "processing an expired unreachable where the expunge threshold is reached" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 0.seconds))
      val unreachableInactiveAfter = f.instance.unreachableStrategy.asInstanceOf[UnreachableEnabled].inactiveAfter
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())

      // Forward time to expire unreachable status
      f.clock.advanceBy(unreachableInactiveAfter)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in an expunge effect" in { result shouldBe a[InstanceUpdateEffect.Expunge] }
      "add a task event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Expunge]
        effect.events(0) match {
          case MesosStatusUpdateEvent(_, _, taskStatus, _, _, _, _, _, _, _, _) =>
            taskStatus should be(TASK_UNREACHABLE)
          case _ => fail("Event did not match MesosStatusUpdateEvent")
        }
      }
    }

    "updated to running" should {
      val f = new Fixture
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, f.mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in no effect" in { result shouldBe a[InstanceUpdateEffect.Noop] }
    }

    "one task is updated to running unhealthy" should {
      val f = new Fixture
      val newMesosStatus = MesosTaskStatusTestHelper.runningUnhealthy(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(f.instance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "update the instance to unhealthy" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.state.healthy should be(Some(false))
      }
      "keep the instance in a running condition" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.state.condition should be(Condition.Running)
      }
    }

    "running an unhealthy task that is updated to healthy again" should {
      val f = new Fixture
      val unhealthyStatus = f.task.status.copy(mesosStatus = Some(MesosTaskStatusTestHelper.runningUnhealthy(f.taskId)))
      val unhealthyTask = f.task.copy(status = unhealthyStatus)
      val unhealthyState = f.instanceState.copy(healthy = Some(false))
      val unhealthyInstance = f.instance.copy(state = unhealthyState, tasksMap = Map(f.taskId -> unhealthyTask))
      val newMesosStatus = MesosTaskStatusTestHelper.runningHealthy(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(unhealthyInstance, newMesosStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unhealthyInstance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "update the instance to unhealthy" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.state.healthy should be(Some(true))
      }
      "keep the instance in a running condition" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.state.condition should be(Condition.Running)
      }
    }

  }

  "An unreachable instance" when {

    "updated to running" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())
      val unreachableStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Unreachable, mesosStatus = Some(mesosTaskStatus))
      val unreachableTask = f.task.copy(status = unreachableStatus)
      val unreachableState = f.instanceState.copy(condition = Condition.Unreachable)
      val unreachableInstance = f.instance.copy(tasksMap = Map(f.taskId -> unreachableTask), state = unreachableState)

      // Update unreachableInstance with running Mesos status.
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInstance, f.mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInstance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "update the instance to running" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.state.condition should be(Condition.Running)
      }
    }

    "update to unknown" should {
      val f = new Fixture(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())
      val unreachableStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Unreachable, mesosStatus = Some(mesosTaskStatus))
      val unreachableTask = f.task.copy(status = unreachableStatus)
      val unreachableState = f.instanceState.copy(condition = Condition.Unreachable)
      val unreachableInstance = f.instance.copy(
        tasksMap = Map(f.taskId -> unreachableTask),
        state = unreachableState,
        runSpec = f.app.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 15.minutes))
      )

      // Update to running
      val unknownMesosTaskStatus = MesosTaskStatusTestHelper.unknown(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInstance, unknownMesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInstance, operation)

      "result in an update for the instance" in {
        result shouldBe a[InstanceUpdateEffect.Update]
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.instance.instanceId should be(unreachableInstance.instanceId)
      }
    }

    "updated to unreachable again" should {
      val f = new Fixture

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())
      val unreachableStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Unreachable, mesosStatus = Some(mesosTaskStatus))
      val unreachableTask = f.task.copy(status = unreachableStatus)
      val unreachableState = f.instanceState.copy(condition = Condition.Unreachable)
      val unreachableStrategy = UnreachableEnabled(inactiveAfter = 30.minutes, expungeAfter = 1.hour)
      val updatedRunSpec = f.app.copy(unreachableStrategy = unreachableStrategy)
      val unreachableInstance =
        f.instance.copy(tasksMap = Map(f.taskId -> unreachableTask), state = unreachableState, runSpec = updatedRunSpec)

      // Move time forward
      f.clock.advanceBy(5.minutes)
      // Update unreachableInstance with unreachable Mesos status.
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInstance, mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInstance, operation)

      "result in no effect" in { result shouldBe a[InstanceUpdateEffect.Noop] }
    }
  }

  "An unreachable inactive instance" when {

    "update to unreachable again" should {
      val f = new Fixture

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())
      val unreachableStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Unreachable, mesosStatus = Some(mesosTaskStatus))
      val unreachableTask = f.task.copy(status = unreachableStatus)
      val unreachableInactiveState = f.instanceState.copy(condition = Condition.UnreachableInactive)
      val unreachableStrategy = UnreachableEnabled(inactiveAfter = 1.minute, expungeAfter = 1.hour)
      val updatedRunSpec = f.app.copy(unreachableStrategy = unreachableStrategy)
      val unreachableInactiveInstance =
        f.instance.copy(tasksMap = Map(f.taskId -> unreachableTask), state = unreachableInactiveState, runSpec = updatedRunSpec)

      // Move time forward
      f.clock.advanceBy(5.minutes)

      // Update unreachableInstance with unreachable Mesos status.
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInactiveInstance, mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInactiveInstance, operation)

      "result in no effect" in { result shouldBe a[InstanceUpdateEffect.Noop] }
    }
  }

  "A terminal instance with goal stopped should not be expunged" in {
    val f = new Fixture

    // Setup staged instance with a staged task
    val app = new AppDefinition(AbsolutePathId("/test"), role = "*")
    val scheduledInstance = Instance.scheduled(app)
    val taskId = Task.Id(scheduledInstance.instanceId)
    val provisionedTasks = Tasks.provisioned(taskId, NetworkInfoPlaceholder(), app.version, Timestamp.now(f.clock))
    val provisionedInstance = scheduledInstance.provisioned(AgentInfoPlaceholder(), app, provisionedTasks, Timestamp.now(f.clock))
    val withStoppedGoal = provisionedInstance.copy(state = provisionedInstance.state.copy(goal = Goal.Stopped))

    val mesosTaskStatus = MesosTaskStatusTestHelper.killed(taskId)
    val operation = InstanceUpdateOperation.MesosUpdate(withStoppedGoal, mesosTaskStatus, f.clock.now())
    val result = InstanceUpdater.mesosUpdate(withStoppedGoal, operation)

    result.isInstanceOf[Expunge] should be(false)
  }

  "An instance with 2 containers" should {

    val f = new Fixture
    var instance: Instance = TestInstanceBuilder
      .newBuilder(AbsolutePathId("/pod"))
      .addTaskStaged(containerName = Some(f.container1.name))
      .addTaskStaged(containerName = Some(f.container2.name))
      .getInstance()

    "have no health info if container1 is healthy and container2 is not Running" in {
      instance = TaskStatusUpdateTestHelper.runningHealthy(instance, Some(f.container1)).wrapped.instance
      instance.state.healthy shouldBe None
    }

    "be considered healthy if container1 is healthy and container2 has no health information" in {
      instance = TaskStatusUpdateTestHelper.running(instance, Some(f.container2)).updatedInstance
      instance = TaskStatusUpdateTestHelper.runningHealthy(instance, Some(f.container1)).updatedInstance
      instance.state.healthy.value shouldBe true
    }

    "be considered healthy if both containers A and B are healthy " in {
      instance = TaskStatusUpdateTestHelper.runningHealthy(instance, Some(f.container1)).updatedInstance
      instance = TaskStatusUpdateTestHelper.runningHealthy(instance, Some(f.container2)).updatedInstance
      instance.state.healthy.value shouldBe true
    }

    "be considered unhealthy if container1 is unhealthy and container2 has no health information" in {
      instance = TaskStatusUpdateTestHelper.runningUnhealthy(instance, Some(f.container1)).updatedInstance
      instance.state.healthy.value shouldBe false
    }

    "be considered unhealthy if container1 is healthy and container2 is unhealthy" in {
      instance = TaskStatusUpdateTestHelper.runningHealthy(instance, Some(f.container1)).updatedInstance
      instance = TaskStatusUpdateTestHelper.runningUnhealthy(instance, Some(f.container2)).updatedInstance
      instance.state.healthy.value shouldBe false
    }

    "not transition to another state if another terminal TaskStatus update for an already terminal task is processed" in {
      instance = TaskStatusUpdateTestHelper.running(instance, Some(f.container1)).updatedInstance
      instance = TaskStatusUpdateTestHelper.finished(instance, Some(f.container2)).updatedInstance

      TaskStatusUpdateTestHelper.unknown(instance, Some(f.container2)).effect shouldBe a[InstanceUpdateEffect.Noop]
      TaskStatusUpdateTestHelper.gone(instance, Some(f.container2)).effect shouldBe a[InstanceUpdateEffect.Noop]
      TaskStatusUpdateTestHelper.dropped(instance, Some(f.container2)).effect shouldBe a[InstanceUpdateEffect.Noop]
      TaskStatusUpdateTestHelper.failed(instance, Some(f.container2)).effect shouldBe a[InstanceUpdateEffect.Noop]
    }
  }

  "keep goal untouched during the mesos update" in {
    val f = new Fixture

    // Setup staged instance with a staged task
    val mesosTaskStatus = MesosTaskStatusTestHelper.staging(f.taskId)
    val stagedStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Staging, mesosStatus = Some(mesosTaskStatus))
    val stagedTask = f.task.copy(status = stagedStatus)
    val stagedState = f.instanceState.copy(condition = Condition.Staging, goal = Goal.Stopped)
    val stagedAndStoppedInstance = f.instance.copy(tasksMap = Map(f.taskId -> stagedTask), state = stagedState)

    // Update to running
    val operation = InstanceUpdateOperation.MesosUpdate(stagedAndStoppedInstance, f.mesosTaskStatus, f.clock.now())
    val result = InstanceUpdater.mesosUpdate(stagedAndStoppedInstance, operation)

    result.asInstanceOf[Update].instance.state.goal should be(Goal.Stopped)
  }

  "suspend reservation when resident instance is terminal" in {
    val f = new Fixture

    val app = AppDefinition(AbsolutePathId("/test"), role = "*")
    val scheduledReserved = TestInstanceBuilder.scheduledWithReservation(app)
    val provisionedTasks = Tasks.provisioned(f.taskId, NetworkInfoPlaceholder(), app.version, Timestamp.now(f.clock))
    val provisionedInstance = scheduledReserved.provisioned(f.agentInfo, app, provisionedTasks, Timestamp(f.clock.instant()))
    val killedOperation = InstanceUpdateOperation.MesosUpdate(
      provisionedInstance,
      Condition.Killed,
      MesosTaskStatusTestHelper.killed(f.taskId),
      Timestamp(f.clock.instant())
    )
    val updated = InstanceUpdater.mesosUpdate(provisionedInstance, killedOperation).asInstanceOf[Update]

    updated.instance.reservation.get.state should be(Suspended)
  }

  "when a TASK_GONE_BY_OPERATOR status update is sent" should {
    "for a single task" should {
      val appId = AbsolutePathId("/test")
      val instance = TestInstanceBuilder.newBuilder(appId).withReservation(Nil).addTaskWithBuilder().taskRunning().build().instance
      val task = instance.tasksMap.values.head.task

      "abandon reservation when all tasks are TASK_GONE_BY_OPERATOR, and the instance agentId matches the tasks" in {
        val clock = new SettableClock()

        val goneOperation = InstanceUpdateOperation.MesosUpdate(
          instance,
          Condition.Gone,
          MesosTaskStatusTestHelper.goneByOperator(task.taskId, task.status.mesosStatus.map(_.getSlaveId)),
          Timestamp(clock.instant())
        )
        val updated = InstanceUpdater.mesosUpdate(instance, goneOperation).asInstanceOf[Update]

        updated.instance.reservation shouldBe None
        updated.instance.agentInfo shouldBe None
        updated.instance.state.condition shouldBe Condition.Scheduled
      }

      "does not abandon the reservation when the instance targets a different slave ID than the task gone update" in {
        val clock = new SettableClock()
        Given("a reserved instance receives a Gone operation")
        val goneOperation = InstanceUpdateOperation.MesosUpdate(
          instance,
          Condition.Gone,
          MesosTaskStatusTestHelper.goneByOperator(task.taskId, task.status.mesosStatus.map(_.getSlaveId)),
          Timestamp(clock.instant())
        )

        val scheduledInstance = InstanceUpdater.mesosUpdate(instance, goneOperation).asInstanceOf[Update].instance

        And("the reserved instance gets a new reservation, but doesn't yet launch tasks")
        val withNewReservation = scheduledInstance.copy(
          reservation =
            Some(Reservation(Nil, Reservation.State.New(timeout = None), Reservation.SimplifiedId(scheduledInstance.instanceId))),
          agentInfo = Some(AgentInfo("new-host", Some("new-agent-id"), None, None, Nil))
        )
        withNewReservation.state.condition shouldBe Condition.Scheduled

        When("an old status update is received again, due to a pending reconciliation attempt")
        val result = InstanceUpdater.mesosUpdate(withNewReservation, goneOperation)

        Then("the result should be a Noop")
        result shouldBe a[InstanceUpdateEffect.Noop]
      }
    }

    "for a pod" should {
      val podId = AbsolutePathId("/test")
      val podInstance = TestInstanceBuilder
        .newBuilder(podId)
        .withReservation(Nil)
        .addTaskWithBuilder()
        .taskRunning(containerName = Some("container-1"))
        .build()
        .addTaskWithBuilder()
        .taskRunning(containerName = Some("container-2"))
        .build()
        .instance

      "not reset the reservation until all of the tasks receive a TASK_GONE_BY_OPERATOR update" in {
        val clock = new SettableClock()
        val goneOperations = podInstance.tasksMap.values.map { task =>
          InstanceUpdateOperation.MesosUpdate(
            podInstance,
            Condition.Gone,
            MesosTaskStatusTestHelper.goneByOperator(task.taskId, task.status.mesosStatus.map(_.getSlaveId)),
            Timestamp(clock.instant())
          )
        }

        val progressivelyApplied = goneOperations.scanLeft(podInstance) { (instance, operation) =>
          InstanceUpdater.mesosUpdate(instance, operation).asInstanceOf[Update].instance
        }

        inside(progressivelyApplied) {
          case Seq(_, firstOp, secondOp) =>
            firstOp.reservation.nonEmpty shouldBe true
            firstOp.agentInfo shouldBe podInstance.agentInfo

            secondOp.reservation shouldBe None
            secondOp.instance.agentInfo shouldBe None
        }
      }

      "reset the reservation if one of the tasks are TASK_GONE_BY_OPERATOR and all the others are terminal" in {
        val clock = new SettableClock()
        val Seq(task1, task2) = podInstance.tasksMap.values.toSeq
        val finishedOperation =
          InstanceUpdateOperation.MesosUpdate(
            podInstance,
            Condition.Finished,
            MesosTaskStatusTestHelper.finished(task1.taskId),
            Timestamp(clock.instant())
          )

        val goneOperation =
          InstanceUpdateOperation.MesosUpdate(
            podInstance,
            Condition.Gone,
            MesosTaskStatusTestHelper.goneByOperator(task2.taskId, task2.status.mesosStatus.map(_.getSlaveId)),
            Timestamp(clock.instant())
          )

        val progressivelyApplied = Seq(finishedOperation, goneOperation).scanLeft(podInstance) { (instance, operation) =>
          InstanceUpdater.mesosUpdate(instance, operation).asInstanceOf[Update].instance
        }

        inside(progressivelyApplied) {
          case Seq(_, postFinishedOp, postGoneOp) =>
            postFinishedOp.reservation.nonEmpty shouldBe true
            postFinishedOp.agentInfo shouldBe podInstance.agentInfo
            postFinishedOp.state.condition shouldBe Condition.Running

            postGoneOp.reservation shouldBe None
            postGoneOp.instance.agentInfo shouldBe None
            postGoneOp.state.condition shouldBe Condition.Scheduled
        }
      }
    }

  }

  class Fixture(unreachableStrategy: UnreachableStrategy = UnreachableStrategy.default(resident = false)) {
    val container1 = MesosContainer(
      name = "container1",
      resources = Resources()
    )
    val container2 = MesosContainer(
      name = "container2",
      resources = Resources()
    )
    val clock = new SettableClock()

    val agentInfo = AgentInfo("localhost", None, None, None, Seq.empty)
    val instanceState = InstanceState(Condition.Running, clock.now(), Some(clock.now()), None, Goal.Running)
    val instanceId = Instance.Id(AbsolutePathId("/my/app"), PrefixInstance, UUID.randomUUID())
    val taskId: Task.Id = Task.EphemeralTaskId(instanceId, None)
    val mesosTaskStatus = MesosTaskStatusTestHelper.runningHealthy(taskId)
    val taskStatus = Task.Status(
      stagedAt = clock.now(),
      startedAt = Some(clock.now()),
      mesosStatus = Some(mesosTaskStatus),
      condition = Condition.Running,
      networkInfo = NetworkInfoPlaceholder()
    )
    val task = Task(taskId, runSpecVersion = clock.now(), status = taskStatus)
    val app = AppDefinition(
      instanceId.runSpecId,
      role = "*",
      versionInfo = VersionInfo.OnlyVersion(clock.now()),
      unreachableStrategy = unreachableStrategy
    )
    val instance = Instance(instanceId, Some(agentInfo), instanceState, Map(taskId -> task), app, None, "*")
  }
}
