package mesosphere.marathon
package core.instance.update

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ InstanceChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.Instance.{ AgentInfo, InstanceState }
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ PathId, UnreachableEnabled, UnreachableStrategy }
import org.apache.mesos.Protos.TaskState.TASK_UNREACHABLE

import scala.concurrent.duration._

class InstanceUpdaterTest extends UnitTest {

  "A staged instance" when {
    "Processing a TASK_RUNNING update for a staged instance" should {
      val f = new Fixture

      // Setup staged instance with a staged task
      val mesosTaskStatus = MesosTaskStatusTestHelper.staging(f.taskId)
      val stagedStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Staging, mesosStatus = Some(mesosTaskStatus))
      val stagedTask = f.task.copy(status = stagedStatus)
      val stagedState = f.instanceState.copy(condition = Condition.Staging)
      val stagedInstance = f.instance.copy(tasksMap = Map(f.taskId -> stagedTask), state = stagedState)

      // Update to running
      val operation = InstanceUpdateOperation.MesosUpdate(stagedInstance, f.mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(stagedInstance, operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
    }
  }

  "A Running instance" when {
    "Processing an Unreachable update" should {
      val f = new Fixture
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId)
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

    "processing an expired unreachable" should {
      val f = new Fixture
      val unreachableInactiveAfter = f.instance.unreachableStrategy.asInstanceOf[UnreachableEnabled].inactiveAfter
      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId, since = f.clock.now())

      // Forward time to expire unreachable status
      f.clock += unreachableInactiveAfter + 1.minute
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
      val f = new Fixture

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId)
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
      val f = new Fixture

      // Setup unreachable instance with a unreachable task
      val mesosTaskStatus = MesosTaskStatusTestHelper.unreachable(f.taskId)
      val unreachableStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Unreachable, mesosStatus = Some(mesosTaskStatus))
      val unreachableTask = f.task.copy(status = unreachableStatus)
      val unreachableState = f.instanceState.copy(condition = Condition.Unreachable)
      val unreachableInstance = f.instance.copy(tasksMap = Map(f.taskId -> unreachableTask), state = unreachableState)

      // Update to running
      val unknownMesosTaskStatus = MesosTaskStatusTestHelper.unknown(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInstance, unknownMesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInstance, operation)

      "result in an expunge for the instance" in {
        result shouldBe a[InstanceUpdateEffect.Expunge]
        val effect = result.asInstanceOf[InstanceUpdateEffect.Expunge]
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
      val unreachableInstance = f.instance.copy(
        tasksMap = Map(f.taskId -> unreachableTask),
        state = unreachableState,
        unreachableStrategy = unreachableStrategy)

      // Move time forward
      f.clock += 5.minutes
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
      val unreachableInactiveInstance = f.instance.copy(
        tasksMap = Map(f.taskId -> unreachableTask),
        state = unreachableInactiveState,
        unreachableStrategy = unreachableStrategy)

      // Move time forward
      f.clock += 5.minutes

      // Update unreachableInstance with unreachable Mesos status.
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInactiveInstance, mesosTaskStatus, f.clock.now())
      val result = InstanceUpdater.mesosUpdate(unreachableInactiveInstance, operation)

      "result in no effect" in { result shouldBe a[InstanceUpdateEffect.Noop] }
    }
  }

  "An instance with 2 containers" should {

    val f = new Fixture
    var instance: Instance = TestInstanceBuilder.newBuilder(PathId("/pod"))
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

  class Fixture {
    val container1 = MesosContainer(
      name = "container1",
      resources = Resources()
    )
    val container2 = MesosContainer(
      name = "container2",
      resources = Resources()
    )
    val clock = new SettableClock()

    val agentInfo = AgentInfo("localhost", None, Seq.empty)
    val instanceState = InstanceState(Condition.Running, clock.now(), Some(clock.now()), None)
    val taskId: Task.Id = Task.Id("uniq")
    val mesosTaskStatus = MesosTaskStatusTestHelper.runningHealthy(taskId)
    val taskStatus = Task.Status(
      stagedAt = clock.now(),
      startedAt = Some(clock.now()),
      mesosStatus = Some(mesosTaskStatus),
      condition = Condition.Running,
      networkInfo = NetworkInfoPlaceholder()
    )
    val task = Task.LaunchedEphemeral(taskId, runSpecVersion = clock.now(), status = taskStatus)
    val instance = Instance(
      Instance.Id("foobar.instance-baz"), agentInfo, instanceState, Map(taskId -> task), clock.now(),
      UnreachableStrategy.default())
  }
}
