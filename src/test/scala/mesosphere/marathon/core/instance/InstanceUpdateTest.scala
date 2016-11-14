package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ InstanceChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId

class InstanceUpdateTest extends UnitTest {

  "A staged instance" when {
    "all tasks become running" should {
      val f = new Fixture

      // Setup staged instance with a staged task
      val mesosTaskStatus = MesosTaskStatusTestHelper.staging(f.taskId)
      val stagedStatus = f.taskStatus.copy(startedAt = None, condition = Condition.Staging, mesosStatus = Some(mesosTaskStatus))
      val stagedTask = f.task.copy(status = stagedStatus)
      val stagedState = f.instanceState.copy(condition = Condition.Staging)
      val stagedInstance = f.instance.copy(tasksMap = Map(f.taskId -> stagedTask), state = stagedState)

      // Update to running
      val operation = InstanceUpdateOperation.MesosUpdate(stagedInstance, f.mesosTaskStatus, f.clock.now())

      val result = stagedInstance.update(operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
    }
  }

  "A running instance" when {
    "updated to unreachable" should {
      val f = new Fixture

      val newMesosStatus = MesosTaskStatusTestHelper.unreachable(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())

      val result = f.instance.update(operation)

      "result in an update effect" in { result shouldBe a[InstanceUpdateEffect.Update] }
      "add an instance changed event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(1) shouldBe a[InstanceChanged]
      }
      "add a task event" in {
        val effect = result.asInstanceOf[InstanceUpdateEffect.Update]
        effect.events(0) match {
          case MesosStatusUpdateEvent(_, _, taskStatus, _, _, _, _, _, _, _, _) =>
            taskStatus should be("TASK_UNREACHABLE")
          case _ => fail("Event did not match MesosStatusUpdateEvent")
        }
      }

    }

    "updated to running" should {
      val f = new Fixture

      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, f.mesosTaskStatus, f.clock.now())

      val result = f.instance.update(operation)

      "result in no effect" in { result shouldBe a[InstanceUpdateEffect.Noop] }
    }

    "on task is updated to running unhealthy" should {
      val f = new Fixture

      val newMesosStatus = MesosTaskStatusTestHelper.runningUnhealthy(f.taskId)
      val operation = InstanceUpdateOperation.MesosUpdate(f.instance, newMesosStatus, f.clock.now())

      val result = f.instance.update(operation)

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

      val result = unhealthyInstance.update(operation)

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

      // Update unreachableInstace with running Mesos status.
      val operation = InstanceUpdateOperation.MesosUpdate(unreachableInstance, f.mesosTaskStatus, f.clock.now())

      val result = unreachableInstance.update(operation)

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

      val result = unreachableInstance.update(operation)

      "result in an expunge for the instance" in {
        result shouldBe a[InstanceUpdateEffect.Expunge]
        val effect = result.asInstanceOf[InstanceUpdateEffect.Expunge]
        effect.instance.instanceId should be(unreachableInstance.instanceId)
      }
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
    val clock = ConstantClock()

    val agentInfo = Instance.AgentInfo("localhost", None, Seq.empty)
    val instanceState = InstanceState(Condition.Running, clock.now(), Some(clock.now()), None)
    val taskId: Task.Id = Task.Id("uniq")
    val mesosTaskStatus = MesosTaskStatusTestHelper.runningHealthy(taskId)
    val taskStatus = Task.Status(
      stagedAt = clock.now(),
      startedAt = Some(clock.now()),
      mesosStatus = Some(mesosTaskStatus),
      condition = Condition.Running,
      networkInfo = NetworkInfo.empty
    )
    val task = Task.LaunchedEphemeral(taskId, agentInfo, runSpecVersion = clock.now(), status = taskStatus)
    val instance = Instance(Instance.Id("foobar.instance-baz"), agentInfo, instanceState, Map(taskId -> task), clock.now())
  }
}
