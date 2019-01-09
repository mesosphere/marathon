package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.stream.scaladsl.Source
import akka.testkit.TestActorRef
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStep}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.{MarathonHttpHealthCheck, PortReference}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.util.CancellableOnce
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Future, Promise}

class TaskReplaceActorTest extends AkkaUnitTest with Eventually with Inspectors {
  import TaskReplaceActor._

  "TaskReplaceActor" should {
    "replace old tasks without health checks" in {
      Given("an app for five instances")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0)
      )

      And("two are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB)

      And("an update is started for a new app version")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("the instance changes are processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame).asInstanceOf[Continue]

      And("and five should be started")
      val newInstances = framePhase0.instances.values.filter(_.runSpecVersion == newApp.version)
      val oldInstances = framePhase0.instances.values.filter(_.runSpecVersion == app.version)

      Then("one old instance should be deleted")
      forExactly(1, oldInstances) { _.state.goal should be(Goal.Decommissioned) }
      forExactly(1, oldInstances) { _.state.goal should be(Goal.Running) }

      newInstances should have size (5)
      forEvery(newInstances) { _.isScheduled should be(true) }

      When("all new instances become running")
      val instanceAKilled = f.killedInstance(instanceA)
      val newRunning = newInstances.map(_ => f.runningInstance(newApp)).toVector
      val nextFrame = Frame(instanceAKilled +: instanceB +: newRunning)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("all old instances should be deleted")
      forExactly(2, framePhase1.instances.values) { oldInstance =>
        oldInstance.runSpecVersion should be(app.version)
        oldInstance.state.goal should be(Goal.Decommissioned)
      }
    }

    "not kill new and already started tasks" in {
      Given("an app with five instances")
      val f = new Fixture
      val app: AppDefinition = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0)
      )

      And("one instance is already running")
      val instanceA = f.runningInstance(app)
      val initialFrame = Frame(instanceA)
      val businessLogic = TaskReplaceActorLogicInstance(app, initialFrame)

      When("the first frame is processed")
      val Continue(nextFrame) = businessLogic.process(0, initialFrame).asInstanceOf[Continue]

      Then("four new instances are started")
      val instances = nextFrame.instances.values
      instances should have size (5)
      forEvery(instances) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Running)
      }
      forExactly(4, instances) { _.isScheduled should be(true) }
      forExactly(1, instances) { _.isScheduled should be(false) }

      And("no instance is killed")
      //forNo(nextFrame.instances.values) { _.state.goal should be(Goal.Decommissioned) }
      forEvery(nextFrame.instances.values) { _.state.goal should not be (Goal.Decommissioned) }
    }

    "replace old tasks with health checks" in {
      Given("an app with five instances")
      val f = new Fixture
      val app: AppDefinition = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.0))

      And("two are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB)

      And("a new app version should be launched")
      val newApp: AppDefinition = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("we process the initial frame")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame).asInstanceOf[Continue]

      Then("one old instance is killed and five new instances are started")
      val instances = framePhase0.instances.values
      forExactly(5, instances) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }
      forExactly(2, instances) { _.runSpecVersion should be(app.version) }
      forExactly(1, instances) { _.state.goal should be(Goal.Decommissioned) }

      When("all instances become running")
      val instanceAKilled = f.killedInstance(instanceA)
      val newRunning = (0 until 5).map(_ => f.runningInstance(newApp)).toVector
      val nextFrame = Frame(instanceAKilled +: instanceB +: newRunning)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame).asInstanceOf[Continue]

      And("next old instance is killed")
      forExactly(2, framePhase1.instances.values) { _.runSpecVersion should be(app.version) }
      forExactly(2, framePhase1.instances.values) { _.state.goal should be(Goal.Decommissioned) }

      When("all new instances become healthy and all old are terminal")
      val nextFrame1 = newRunning.foldLeft(framePhase1) { (acc, i) => acc.updateHealth(i.instanceId, true) }
      val instanceBKilled = f.killedInstance(instanceB)
      val result = businessLogic.process(2, nextFrame1.withInstance(instanceBKilled))

      Then("we stop")
      result should be(Stop)
    }

    "replace and scale down from more than new minCapacity" in {
      Given("an app with two instances and minimum capacity")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 2,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0))

      And("two instances are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB)

      And("an app version update")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("we process the initial frame")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("no old instance is killed")
      forEvery(framePhase0.instances.values) { _.state.goal should not be (Goal.Decommissioned) }

      And("two new instances are scheduled")
      forExactly(2, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }
      val (oldInstances, newInstances) = framePhase0.instances.values.partition(_.runSpecVersion == app.version)
      oldInstances should have size (2)
      newInstances should have size (2)

      When("the first new instance become running")
      val newInstanceC = f.runningInstance(newApp)
      val nextFrame = Frame(oldInstances.toVector :+ newInstances.head :+ newInstanceC)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("only one old instance is killed")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      When("the last new instance becomes running and the first old instance is dead")
      val newInstanceD = f.runningInstance(newApp)
      val nextFrame1 = Frame(oldInstances.head, newInstanceC, newInstanceD)
      val Continue(framePhase2) = businessLogic.process(2, nextFrame1)

      Then("the last old instance is killed")
      framePhase2.instances should have size (3)
      forExactly(1, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }
    }

    "replace tasks with minimum running number of tasks" in {
      Given("an app with three instances and minimum running number of tasks")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.5)
      )

      And("three instances are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB, instanceC)

      And("a version update")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("the initial frame is processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("all new instances are scheduled directly")
      forExactly(3, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      And("ceiling(minimumHealthCapacity * 3) = 2 old instances are left running")
      forExactly(2, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Running)
      }
      forExactly(1, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      When("the first new instance becomes healthy")
      val firstNewInstance = framePhase0.instances.values.find(_.isScheduled).get
      val firstNewInstanceRunning = f.runningInstance(firstNewInstance)
      val nextFrame = framePhase0.withInstance(firstNewInstanceRunning)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("the second old instance is killed")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Running)
      }
      forExactly(2, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      When("the second new instance becomes healthy")
      val secondNewInstance = framePhase1.instances.values.find(_.isScheduled).get
      val secondNewInstanceRunning = f.runningInstance(secondNewInstance)
      val nextFrame1 = framePhase1.withInstance(secondNewInstanceRunning)
      val Continue(framePhase2) = businessLogic.process(2, nextFrame1)

      Then("the last old instance is killed")
      forExactly(3, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      When("the the third new instance becomes healthy and all old are terminal")
      val thirdNewInstance = framePhase2.instances.values.find(_.isScheduled).get
      val thirdNewInstanceRunning = f.runningInstance(thirdNewInstance)
      val nextFrame2 = Frame(firstNewInstanceRunning, secondNewInstanceRunning, thirdNewInstanceRunning)
      val result = businessLogic.process(3, nextFrame2)

      Then("we are done")
      result should be(Stop)
    }

    "replace tasks during rolling upgrade *without* over-capacity" in {
      Given("an app with three instances and not over-capacity")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.5, 0.0)
      )

      And("three instances are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB, instanceC)

      And("a version update")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("the initial frame is processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("ceiling(minimumHealthCapacity * 3) = 2 are left running, ie 1 is killed immediately")
      forExactly(1, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("no new instance is queued")
      forEvery(framePhase0.instances.values) { _.runSpecVersion should be(app.version) }

      When("the first old instance is dead")
      val firstOldInstance = framePhase0.instances.values.find(_.state.goal == Goal.Decommissioned).get
      val firstOldInstanceKilled = f.killedInstance(firstOldInstance)
      val nextFrame = framePhase0.withInstance(firstOldInstanceKilled)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame)

      Then("only one instance is queued")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      And("the second old instance is killed")
      forExactly(2, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      When("the first new instance becomes healthy and second old is terminal")
      val firstNewInstance = framePhase1.instances.values.find(_.isScheduled).get
      val firstNewInstanceRunning = f.runningInstance(firstNewInstance)
      val secondOldInstance = framePhase1.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val secondOldInstanceKilled = f.killedInstance(secondOldInstance)
      val nextFrame1 = framePhase1.withInstance(firstNewInstanceRunning).withInstance(secondOldInstanceKilled)
      val Continue(framePhase2) = businessLogic.process(2, nextFrame1)

      Then("the third old instance is killed")
      forExactly(3, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("the second new instance is queued")
      forExactly(1, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the second new instance becomes healthy and third old is terminal")
      val secondNewInstance = framePhase2.instances.values.find(_.isScheduled).get
      val secondNewInstanceRunning = f.runningInstance(secondNewInstance)
      val thirdOldInstance = framePhase2.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val thirdOldInstanceKilled = f.killedInstance(thirdOldInstance)
      val nextFrame2 = framePhase2.withInstance(secondNewInstanceRunning).withInstance(thirdOldInstanceKilled)
      val Continue(framePhase3) = businessLogic.process(2, nextFrame2)

      Then("the third new instance is queued")
      forExactly(1, framePhase3.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the third instance becomes healthy")
      val thirdNewInstance = framePhase3.instances.values.find(_.isScheduled).get
      val thirdNewInstanceRunning = f.runningInstance(thirdNewInstance)
      val nextFrame3 = framePhase3.withInstance(thirdNewInstanceRunning)
      val result = businessLogic.process(3, nextFrame3)

      Then("we are done")
      result should be(Stop)
    }

    "replace tasks during rolling upgrade *with* minimal over-capacity" in {
      Given("an app with three instances an one over-capacity")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(1.0, 0.0) // 1 task over-capacity is ok
      )

      And("three instances are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB, instanceC)

      And("a version update")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("the initial frame is processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("no old instance is killed immediately")
      forEvery(framePhase0.instances.values) { _.state.goal should not be (Goal.Decommissioned) }

      And("the first new instance is queued")
      forExactly(1, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the first new instance becomes healthy")
      val firstNewInstance = framePhase0.instances.values.find(_.isScheduled).get
      val firstNewInstanceRunning = f.runningInstance(firstNewInstance)
      val nextFrame0 = framePhase0.withInstance(firstNewInstanceRunning)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame0)

      Then("the first old instance is killed")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("no new instance is queued")
      forEvery(framePhase1.instances.values) { _.isScheduled should be(false) }

      When("first old instance is killed")
      val firstOldInstance = framePhase1.instances.values.find(_.state.goal == Goal.Decommissioned).get
      val firstOldInstanceKilled = f.killedInstance(firstOldInstance)
      val nextFrame1 = framePhase1.withInstance(firstOldInstanceKilled)
      val Continue(framePhase2) = businessLogic.process(2, nextFrame1)

      Then("the second old instance is killed")
      forExactly(2, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("the second new instance is queued")
      forExactly(1, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the second new instance becomes healthy and the second old is killed")
      val secondOldInstance = framePhase2.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val secondOldInstanceKilled = f.killedInstance(secondOldInstance)
      val secondNewInstance = framePhase2.instances.values.find(_.isScheduled).get
      val secondNewInstanceRunning = f.runningInstance(secondNewInstance)
      val nextFrame2 = framePhase2.withInstance(secondOldInstanceKilled).withInstance(secondNewInstanceRunning)
      val Continue(framePhase3) = businessLogic.process(3, nextFrame2)

      Then("the third old instance is killed")
      forExactly(3, framePhase3.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("the last instance is queued")
      forExactly(1, framePhase3.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the third old is killed and the last becomes healthy")
      val thirdOldInstance = framePhase3.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val thirdOldInstanceKilled = f.killedInstance(thirdOldInstance)
      val thirdNewInstance = framePhase3.instances.values.find(_.isScheduled).get
      val thirdNewInstanceRunning = f.runningInstance(thirdNewInstance)
      val nextFrame3 = framePhase3.withInstance(thirdOldInstanceKilled).withInstance(thirdNewInstanceRunning)
      val result = businessLogic.process(4, nextFrame3)

      Then("we are done")
      result should be(Stop)
    }

    "replace tasks during rolling upgrade with 2/3 over-capacity" in {
      Given("an app with three instances and 2/3 over-capacity")
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(1.0, 0.7)
      )

      And("three instances are running")
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)
      val initialFrame = Frame(instanceA, instanceB, instanceC)

      And("a version update")
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val businessLogic = TaskReplaceActorLogicInstance(newApp, initialFrame)

      When("the initial frame is processed")
      val Continue(framePhase0) = businessLogic.process(0, initialFrame)

      Then("no old instance is killed")
      forEvery(framePhase0.instances.values) { _.state.goal should not be (Goal.Decommissioned) }

      And("the first two new instances are queued")
      forExactly(2, framePhase0.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the first new instance becomes healthy")
      val firstNewInstance = framePhase0.instances.values.find(_.isScheduled).get
      val firstNewInstanceRunning = f.runningInstance(firstNewInstance)
      val nextFrame0 = framePhase0.withInstance(firstNewInstanceRunning)
      val Continue(framePhase1) = businessLogic.process(1, nextFrame0)

      Then("the first old instance is killed")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("the no new instance is queued")
      forExactly(1, framePhase1.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the second new instance becomes healthy and the first old instance is killed")
      val secondNewInstance = framePhase1.instances.values.find(_.isScheduled).get
      val secondNewInstanceRunning = f.runningInstance(secondNewInstance)
      val firstOldInstance = framePhase1.instances.values.find(_.state.goal == Goal.Decommissioned).get
      val firstOldInstanceKilled = f.killedInstance(firstOldInstance)
      val nextFrame1 = framePhase1.withInstance(secondNewInstanceRunning).withInstance(firstOldInstanceKilled)
      val Continue(framePhase2) = businessLogic.process(1, nextFrame1)

      Then("the second old instance is killed")
      forExactly(2, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("the third new instance is queued")
      forExactly(1, framePhase2.instances.values) { instance =>
        instance.runSpecVersion should be(newApp.version)
        instance.isScheduled should be(true)
      }

      When("the last new instance becomes healthy and the second old is killed")
      val thirdNewInstance = framePhase2.instances.values.find(_.isScheduled).get
      val thirdNewInstanceRunning = f.runningInstance(thirdNewInstance)
      val secondOldInstance = framePhase2.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val secondOldInstanceKilled = f.killedInstance(secondOldInstance)
      val nextFrame2 = framePhase2.withInstance(thirdNewInstanceRunning).withInstance(secondOldInstanceKilled)
      val Continue(framePhase3) = businessLogic.process(1, nextFrame2)

      Then("the last old instance is killed")
      forExactly(3, framePhase3.instances.values) { instance =>
        instance.runSpecVersion should be(app.version)
        instance.state.goal should be(Goal.Decommissioned)
      }

      And("no new instance is queued")
      forEvery(framePhase3.instances.values) { _.isScheduled should not be (true) }

      When("the last old instance is killed")
      val thirdOldInstance = framePhase3.instances.values.find(i => i.state.goal == Goal.Decommissioned && i.isRunning).get
      val thirdOldInstanceKilled = f.killedInstance(thirdOldInstance)
      val nextFrame3 = framePhase3.withInstance(thirdOldInstanceKilled)
      val result = businessLogic.process(1, nextFrame3)

      Then("we are done")
      result should be(Stop)
    }

    // TODO(karsten): migrate
    "downscale tasks during rolling upgrade with 1 over-capacity" ignore {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0, maximumOverCapacity = 0.3)
      )

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)
      val instanceD = f.runningInstance(app)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB, instanceC, instanceD)
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))
      f.tracker.get(instanceD.instanceId) returns Future.successful(Some(instanceD))
      f.queue.add(newApp, 1) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // one task is killed directly because we are over capacity
      eventually {
        verify(f.tracker).setGoal(instanceA.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      }

      // the kill is confirmed (see answer above) and the first new task is queued
      eventually {
        verify(f.queue, times(1)).resetDelay(newApp)
      }

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.tracker, times(2)).setGoal(any, any, any)
      }
      eventually {
        verify(f.queue, times(2)).add(newApp, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.tracker, times(3)).setGoal(any, any, any)
      }
      eventually {
        verify(f.queue, times(3)).add(newApp, 1)
      }

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.tracker, times(4)).setGoal(any, any, any)
      }

      promise.future.futureValue

      // all remaining old tasks are killed
      verify(f.tracker).setGoal(instanceD.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(f.tracker).setGoal(instanceB.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(f.tracker).setGoal(instanceC.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)

      verify(f.queue, times(3)).add(newApp, 1)

      expectTerminated(ref)
    }

    // TODO(karsten): migrate
    "stop the actor if all tasks are replaced already" ignore {
      Given("An app without health checks and readiness checks, as well as 2 tasks of this version")
      val f = new Fixture
      val app = AppDefinition(id = "/myApp".toPath, instances = 2)
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB)
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)

      Then("The replace actor finishes immediately")
      expectTerminated(ref)
      promise.future.futureValue
    }

    // TODO(karsten): migrate
    "wait for readiness checks if all tasks are replaced already" ignore {
      Given("An app without health checks but readiness checks, as well as 1 task of this version")
      val f = new Fixture
      val check = ReadinessCheck()
      val port = PortDefinition(0, name = Some(check.portName))
      val app = AppDefinition(id = "/myApp".toPath, instances = 1, portDefinitions = Seq(port), readinessChecks = Seq(check))
      val instance = f.runningInstance(app)
      f.tracker.specInstancesSync(app.id) returns Seq(instance)
      f.tracker.get(instance.instanceId) returns Future.successful(Some(instance))
      val (_, readyCheck) = f.readinessResults(instance, check.name, ready = true)
      f.readinessCheckExecutor.execute(any[ReadinessCheckExecutor.ReadinessCheckSpec]) returns readyCheck
      val promise = Promise[Unit]()

      When("The replace actor is started")
      f.replaceActor(app, promise)

      Then("It needs to wait for the readiness checks to pass")
      promise.future.futureValue
    }

    // TODO(karsten): migrate
    " wait for the readiness checks and health checks if all tasks are replaced already" ignore {
      Given("An app without health checks but readiness checks, as well as 1 task of this version")
      val f = new Fixture
      val ready = ReadinessCheck()

      val port = PortDefinition(0, name = Some(ready.portName))
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 1,
        portDefinitions = Seq(port),
        readinessChecks = Seq(ready),
        healthChecks = Set(MarathonHttpHealthCheck())
      )
      val instance = f.runningInstance(app)
      f.tracker.specInstancesSync(app.id) returns Seq(instance)
      f.tracker.get(instance.instanceId) returns Future.successful(Some(instance))
      val (_, readyCheck) = f.readinessResults(instance, ready.name, ready = true)
      f.readinessCheckExecutor.execute(any[ReadinessCheckExecutor.ReadinessCheckSpec]) returns readyCheck
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)
      ref ! InstanceHealthChanged(instance.instanceId, app.version, app.id, healthy = Some(true))

      Then("It needs to wait for the readiness checks to pass")
      expectTerminated(ref)
      promise.future.futureValue
    }

    // TODO(karsten): migrate
    "wait until the tasks are killed" ignore {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB)
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 5) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      for (_ <- 0 until newApp.instances)
        ref ! f.instanceChanged(newApp, Running)

      verify(f.queue, timeout(1000)).resetDelay(newApp)

      promise.future.futureValue

      verify(f.tracker).setGoal(instanceA.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
      verify(f.tracker).setGoal(instanceB.instanceId, Goal.Decommissioned, GoalChangeReason.Upgrading)
    }

    // TODO(karsten): migrate
    "wait for health and readiness checks for new tasks" ignore {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 1,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck()),
        readinessChecks = Seq(ReadinessCheck()),
        upgradeStrategy = UpgradeStrategy(1.0, 1.0)
      )

      val instance = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instance)
      f.tracker.get(instance.instanceId) returns Future.successful(Some(instance))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 1) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      val newInstanceId = Instance.Id.forRunSpec(newApp.id)
      val newTaskId = Task.Id(newInstanceId)

      //unhealthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(false))
      eventually {
        verify(f.tracker, never).setGoal(any, any, any)
      }

      //unready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = false, None)
      eventually {
        verify(f.tracker, never).setGoal(any, any, any)
      }

      //healthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(true))
      eventually {
        verify(f.tracker, never).setGoal(any, any, any)
      }

      //ready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = true, None)
      eventually {
        verify(f.tracker, once).setGoal(any, any, any)
      }

      promise.future.futureValue
    }
  }
  case class TaskReplaceActorLogicInstance(runSpec: RunSpec, initialFrame: Frame) extends TaskReplaceActorLogic {
    override def initiateReadinessCheck(instance: Instance): Unit = ???

    override val hasReadinessChecks: Boolean = false
    override val status = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    override val ignitionStrategy = TaskReplaceActor.computeRestartStrategy(runSpec, initialFrame.instances.size)
  }

  class Fixture {
    val deploymentsManager: TestActorRef[Actor] = TestActorRef[Actor](Props.empty)
    val deploymentStatus = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    val queue: LaunchQueue = mock[LaunchQueue]
    val tracker: InstanceTracker = mock[InstanceTracker]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val hostName = "host.some"
    val hostPorts = Seq(123)

    tracker.setGoal(any, any, any) answers { args =>
      def sendKilled(instance: Instance, goal: Goal): Unit = {
        val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed, goal = goal))
        val events = InstanceChangedEventsGenerator.events(updatedInstance, None, Timestamp(0), Some(instance.state))
        events.foreach(system.eventStream.publish)
      }

      val instanceId = args(0).asInstanceOf[Instance.Id]
      val maybeInstance = tracker.get(instanceId).futureValue
      maybeInstance.map { instance =>
        val goal = args(1).asInstanceOf[Goal]
        sendKilled(instance, goal)
        Future.successful(Done)
      }.getOrElse {
        Future.failed(throw new IllegalArgumentException(s"instance $instanceId is not ready in instance tracker when querying"))
      }
    }

    def runningInstance(app: AppDefinition): Instance = {
      val instance = TestInstanceBuilder.newBuilder(app.id, version = app.version)
        .addTaskWithBuilder().taskRunning().withNetworkInfo(hostName = Some(hostName), hostPorts = hostPorts).build()
        .getInstance()
      val updatedState = instance.state.copy(healthy = Some(true))
      instance.copy(state = updatedState)
    }

    def runningInstance(instance: Instance): Instance = {
      val updatedState = instance.state.copy(condition = Condition.Running, healthy = Some(true))
      instance.copy(state = updatedState)
    }

    def killedInstance(instance: Instance): Instance = {
      val updatedState = instance.state.copy(condition = Condition.Killed, goal = Goal.Decommissioned)
      instance.copy(state = updatedState, tasksMap = Map.empty)
    }

    def readinessResults(instance: Instance, checkName: String, ready: Boolean): (Cancellable, Source[ReadinessCheckResult, Cancellable]) = {
      val cancellable = new CancellableOnce(() => ())
      val source = Source(instance.tasksMap.values.map(task => ReadinessCheckResult(checkName, task.taskId, ready, None)).toList).
        mapMaterializedValue { _ => cancellable }
      (cancellable, source)
    }

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val state = InstanceState(Condition.Running, Timestamp.now(), None, None, Goal.Running)
      val instance: Instance = Instance(instanceId, None, state, Map.empty, app, None)

      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = Some(healthy))
    }
    def replaceActor(app: AppDefinition, promise: Promise[Unit]): ActorRef = system.actorOf(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
