package mesosphere.marathon
package core.deployment.impl

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Failed
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStep}
import mesosphere.marathon.core.deployment.impl.FrameProcessor.{Continue, Stop}
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.health.MesosCommandHealthCheck
import mesosphere.marathon.core.instance.Goal.Decommissioned
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{AppDefinition, Command, Timestamp}
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually

class TaskStartActorTest extends AkkaUnitTest with Eventually with Inspectors {
  "TaskStartActor" should {

    "Start success no items in the queue" in {
      val app = AppDefinition("/myApp".toPath, instances = 5)

      val initialFrame = Frame()
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      framePhase0.operations should have size (5)
      forExactly(5, framePhase0.instances.values) { _.isScheduled should be(true) }
    }

    "Start success with one task left to launch" in {
      val app = AppDefinition("/myApp".toPath, instances = 5)

      val instances: Seq[Instance] = Seq(Instance.scheduled(app))
      val initialFrame = Frame(instances)
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      framePhase0.operations should have size (4)
      forExactly(5, framePhase0.instances.values) { _.isScheduled should be(true) }
    }

    "Start success with existing task in launch queue" in {
      val app = AppDefinition("/myApp".toPath, instances = 5)

      val instances: Seq[Instance] = Seq(TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning().getInstance())
      val initialFrame = Frame(instances)
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      framePhase0.operations should have size (4)
      forExactly(4, framePhase0.instances.values) { _.isScheduled should be(true) }
    }

    "Start success with no instances to start" in {
      val app = AppDefinition("/myApp".toPath, instances = 0)

      val initialFrame = Frame()
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      val result = businessLogic.process(0, initialFrame)

      result should be(Stop)
    }

    "Start with health checks" in {
      Given("an app with health checks")
      val app = AppDefinition(
        "/myApp".toPath,
        instances = 5,
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true")))
      )

      And("five running instances")
      val instances: Seq[Instance] = (0 until app.instances).map(_ => TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning().getInstance()).toSeq
      val initialFrame = Frame(instances)
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      When("the initial frame is processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("we are not done")
      framePhase0.operations should have size (0)

      Then("all instances become healthy")
      val nextFrame = framePhase0.instances.foldLeft(framePhase0) { case (acc, (id, _)) => acc.updateHealth(id, true) }

      Then("we are done")
      businessLogic.process(1, nextFrame) should be(Stop)
    }

    "Start with health checks with no instances to start" in {
      val app = AppDefinition(
        "/myApp".toPath,
        instances = 0,
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true")))
      )
      val initialFrame = Frame()
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      businessLogic.process(0, initialFrame) should be(Stop)
    }

    "task fails to start but the goal of instance is still running" in {
      Given("an app with five instances")
      val f = new Fixture
      val app = AppDefinition("/myApp".toPath, instances = 2)

      val initialFrame = Frame()
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      And("the logic scheduled five instances")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)
      framePhase0.operations should have size (2)
      forExactly(2, framePhase0.instances.values) { _.isScheduled should be(true) }

      When("one instance fails")
      val oldInstances = framePhase0.instances.values.to[Seq]
      val failedInstance = f.instanceNewCondition(app.version, oldInstances.head.instanceId, Failed)
      val nextFrame = Frame(failedInstance +: oldInstances.tail)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("no new instance is scheduled")
      framePhase1.operations should have size (0)
    }

    "task fails and the instance is decommissioned while starting an app" in {
      Given("an app with five instances")
      val f = new Fixture
      val app = AppDefinition("/myApp".toPath, instances = 2)

      val initialFrame = Frame()
      val businessLogic = TaskStartActorInstance(app, initialFrame)

      And("the logic scheduled five instances")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)
      framePhase0.operations should have size (2)
      forExactly(2, framePhase0.instances.values) { _.isScheduled should be(true) }

      When("one instance fails")
      val oldInstances = framePhase0.instances.values.to[Seq]
      val failedInstance = f.instanceNewCondition(app.version, oldInstances.head.instanceId, Failed, Decommissioned)
      val nextFrame = Frame(failedInstance +: oldInstances.tail)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("no new instance is scheduled")
      framePhase1.instances should have size (3)
      framePhase1.operations should have size (1)
      forExactly(2, framePhase1.instances.values) { _.isScheduled should be(true) }
    }
  }

  case class TaskStartActorInstance(runSpec: AppDefinition, initialFrame: Frame) extends TaskReplaceBehaviour with BaseReadinessScheduling {
    var readinessChecksInitiated: Int = 0
    override def initiateReadinessCheck(instance: Instance): Unit = readinessChecksInitiated += 1

    override val status = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    override val ignitionStrategy = computeRestartStrategy(runSpec, initialFrame.instances.size)
  }

  class Fixture {

    def instanceNewCondition(version: Timestamp, id: Instance.Id, condition: Condition, goal: Goal = Goal.Running): Instance = {
      val instance: Instance = mock[Instance]
      instance.instanceId returns id
      instance.state returns Instance.InstanceState(condition, Timestamp.now(), None, None, goal)
      instance.runSpecVersion returns version
      instance
    }
  }
}
