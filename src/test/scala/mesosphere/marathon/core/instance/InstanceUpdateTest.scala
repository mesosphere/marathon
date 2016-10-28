package mesosphere.marathon.core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.event.{ InstanceChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId

class InstanceUpdateTest extends UnitTest {

  val f = new Fixture
  var instance: Instance = _

  override protected def beforeEach(): Unit = {
    instance = {
      TestInstanceBuilder.newBuilder(PathId("/pod"))
        .addTaskStaged(containerName = Some(f.container1.name))
        .addTaskStaged(containerName = Some(f.container2.name))
        .getInstance()
    }
  }

  "A running instance" when {
    "updated to unreachable" should {
      val builder = TestInstanceBuilder.newBuilder(PathId("/app"))
      val instance = builder.addTaskRunning().getInstance()
      val update = TaskStatusUpdateTestHelper.unreachable(instance)

      val result = instance.update(update.operation)

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

  }

  "An instance with 2 containers" should {
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
}
