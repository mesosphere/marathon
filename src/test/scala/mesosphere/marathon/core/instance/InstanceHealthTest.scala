package mesosphere.marathon.core.instance

import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId
import org.scalatest._

class InstanceHealthTest extends WordSpecLike
    with Matchers
    with GivenWhenThen
    with OptionValues
    with BeforeAndAfterEach {

  val f = new Fixture
  var instance: Instance = _

  override protected def beforeEach(): Unit = {
    instance = {
      TestInstanceBuilder.newBuilder(PathId("/pod"))
        .addTaskStaged(container = Some(f.container1))
        .addTaskStaged(container = Some(f.container2))
        .getInstance()
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
