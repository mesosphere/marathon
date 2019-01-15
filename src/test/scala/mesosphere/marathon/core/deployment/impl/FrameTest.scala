package mesosphere.marathon
package core.deployment.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.state.{AppDefinition, PathId}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class FrameTest extends UnitTest with GeneratorDrivenPropertyChecks {

  "A frame" should {
    "update the health even without any instances" in {
      forAll { (health: Boolean) =>
        val instanceId = Instance.Id.forRunSpec(PathId("/myApp"))
        val updatedFrame = Frame().updateHealth(instanceId, health)
        updatedFrame.instancesHealth should have size (1)
        updatedFrame.instancesHealth should contain(instanceId -> health)
      }
    }

    "update the readiness even without any instances" in {
      forAll { (ready: Boolean) =>
        val instanceId = Instance.Id.forRunSpec(PathId("/myApp"))
        val updatedFrame = Frame().updateReadiness(instanceId, ready)
        updatedFrame.instancesReady should have size (1)
        updatedFrame.instancesReady should contain(instanceId -> ready)
      }
    }

    "update the goal and record the operation" in {
      val instanceId = Instance.Id.forRunSpec(PathId("/myApp"))
      val instance = TestInstanceBuilder.newBuilderWithInstanceId(instanceId = instanceId).decommissioned().getInstance()
      val updatedFrame = Frame(instance).setGoal(instanceId, Goal.Running)
      updatedFrame.operations should have size (1)
      updatedFrame.instances(instanceId).state.goal should be(Goal.Running)
    }

    "flush all operations and retain state" in {
      val instanceId = Instance.Id.forRunSpec(PathId("/myApp"))
      val instance = TestInstanceBuilder.newBuilderWithInstanceId(instanceId = instanceId).decommissioned().getInstance()
      val initialFrame = Frame(instance).setGoal(instanceId, Goal.Running)
      initialFrame.operations should have size (1)

      val flushedFrame = initialFrame.withoutOperations()
      flushedFrame.operations should have size (0)
      flushedFrame.instances should be(initialFrame.instances)
      flushedFrame.instancesReady should be(initialFrame.instancesReady)
      flushedFrame.instancesHealth should be(initialFrame.instancesHealth)
    }

    "construct a frame without health from instance map" in {
      val f = new Fixture()
      val instance = f.runningInstance(AppDefinition(id = PathId("/myApp")), None)
      val initialFrame = Frame(Map(instance.instanceId -> instance))

      initialFrame.instances should contain only (instance.instanceId -> instance)
      initialFrame.instancesHealth should contain only (instance.instanceId -> false)
      initialFrame.instancesReady should be('empty)
    }

    "construct a frame without health from instance sequence" in {
      val f = new Fixture()
      val instance = f.runningInstance(AppDefinition(id = PathId("/myApp")), None)
      val initialFrame = Frame(Seq(instance))

      initialFrame.instances should contain only (instance.instanceId -> instance)
      initialFrame.instancesHealth should contain only (instance.instanceId -> false)
      initialFrame.instancesReady should be('empty)
    }

    "construct a frame with health from instance map" in {
      forAll { (health: Boolean) =>
        val f = new Fixture()
        val instance = f.runningInstance(AppDefinition(id = PathId("/myApp")), Some(health))
        val initialFrame = Frame(Map(instance.instanceId -> instance))

        initialFrame.instances should contain only (instance.instanceId -> instance)
        initialFrame.instancesHealth should contain only (instance.instanceId -> health)
        initialFrame.instancesReady should be('empty)
      }
    }

    "construct a frame with health from instance sequence" in {
      forAll { (health: Boolean) =>
        val f = new Fixture()
        val instance = f.runningInstance(AppDefinition(id = PathId("/myApp")), Some(health))
        val initialFrame = Frame(Seq(instance))

        initialFrame.instances should contain only (instance.instanceId -> instance)
        initialFrame.instancesHealth should contain only (instance.instanceId -> health)
        initialFrame.instancesReady should be('empty)
      }
    }
  }

  class Fixture {

    def runningInstance(app: AppDefinition, health: Option[Boolean] = None): Instance = {
      val instance = TestInstanceBuilder.newBuilder(app.id, version = app.version)
        .addTaskWithBuilder().taskRunning().build()
        .getInstance()
      val updatedState = instance.state.copy(healthy = health)
      instance.copy(state = updatedState)
    }
  }

  // TODO(karsten): Frame.add should behave the same as LaunchQueue.Add
}
