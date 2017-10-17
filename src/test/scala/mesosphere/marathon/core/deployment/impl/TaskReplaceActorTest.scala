package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.{ Actor, Props }
import akka.testkit.TestActorRef
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.{ KillServiceMock, Task }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.mockito.{ Mockito }
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import rx.lang.scala.Observable

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

class TaskReplaceActorTest extends AkkaUnitTest with Eventually {
  "TaskReplaceActor" should {
    "Replace without health checks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 5) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      for (_ <- 0 until newApp.instances)
        ref ! f.instanceChanged(newApp, Running)

      promise.future.futureValue
      verify(f.queue).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)

      expectTerminated(ref)
    }

    "New and already started tasks should not be killed" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))

      val instanceA = f.runningInstance(app)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(eq(newApp), any) returns Future.successful(Done)

      val instanceC = f.runningInstance(newApp)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceC)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      for (_ <- 0 until newApp.instances)
        ref ! f.instanceChanged(newApp, Running)

      promise.future.futureValue
      verify(f.queue).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should not contain instanceC.instanceId

      expectTerminated(ref)
    }

    "Replace with health checks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.0))

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)

      when(f.tracker.specInstancesSync(app.id)).thenReturn(Seq(instanceA, instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 5) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      for (_ <- 0 until newApp.instances)
        ref ! f.healthChanged(newApp, healthy = true)

      promise.future.futureValue
      verify(f.queue).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)

      expectTerminated(ref)
    }

    "Replace and scale down from more than new minCapacity" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 2,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0))
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)

      when(f.tracker.specInstancesSync(app.id)).thenReturn(Seq(instanceA, instanceB, instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 1) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      eventually {
        f.killService.numKilled should be(1)
      }

      ref ! f.instanceChanged(newApp, Running)
      eventually {
        f.killService.numKilled should be(2)
      }

      ref ! f.instanceChanged(newApp, Running)
      eventually { app: AppDefinition => verify(f.queue, times(2)).addAsync(app) }

      promise.future.futureValue

      eventually {
        f.killService.numKilled should be(3)
      }
      verify(f.queue).resetDelay(newApp)

      expectTerminated(ref)
    }

    "Replace with minimum running tasks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.5)
      )

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB, instanceC)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 3) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // all new tasks are queued directly
      eventually { app: AppDefinition => verify(f.queue, times(3)).addAsync(app) }

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      assert(f.killService.numKilled == 1)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }

      // second new task becomes healthy and the last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }

      // third new task becomes healthy
      ref ! f.healthChanged(newApp, healthy = true)
      f.killService.numKilled should be(3)

      promise.future.futureValue

      // all old tasks are killed
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)

      expectTerminated(ref)
    }

    "Replace with rolling upgrade without over-capacity" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(0.5, 0.0)
      )

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB, instanceC)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 1) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).addAsync(_: AppDefinition, 1)
      }

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      assert(f.killService.numKilled == 1)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      eventually {
        queueOrder.verify(f.queue).addAsync(_: AppDefinition, 1)
      }

      // second new task becomes healthy and the last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      eventually {
        queueOrder.verify(f.queue).addAsync(_: AppDefinition, 1)
      }

      // third new task becomes healthy
      ref ! f.healthChanged(newApp, healthy = true)
      f.killService.numKilled should be(3)

      promise.future.futureValue

      // all old tasks are killed
      verify(f.queue).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)

      expectTerminated(ref)
    }

    "Replace with rolling upgrade with minimal over-capacity" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(1.0, 0.0) // 1 task over-capacity is ok
      )

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB, instanceC)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 1) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly, all old still running
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }
      assert(f.killService.numKilled == 0)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(1)
      }
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      queueOrder.verify(f.queue, never).add(_: AppDefinition, 1)

      promise.future.futureValue

      // all old tasks are killed
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)

      expectTerminated(ref)
    }

    "Replace with rolling upgrade with 2/3 over-capacity" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 3,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
        upgradeStrategy = UpgradeStrategy(1.0, 0.7)
      )

      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      val instanceC = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB, instanceC)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(eq(newApp), any) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // two tasks are queued directly, all old still running
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 2)
      }
      assert(f.killService.numKilled == 0)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(1)
      }
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      queueOrder.verify(f.queue, never).add(_: AppDefinition, 1)

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      queueOrder.verify(f.queue, never).add(_: AppDefinition, 1)

      promise.future.futureValue

      // all old tasks are killed
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)

      expectTerminated(ref)
    }

    "Downscale with rolling upgrade with 1 over-capacity" in {
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
      f.queue.addAsync(newApp, 1) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // one task is killed directly because we are over capacity
      eventually {
        f.killService.killed should contain(instanceA.instanceId)
      }

      // the kill is confirmed (see answer above) and the first new task is queued
      eventually {
        verify(f.queue, times(1)).addAsync(newApp, 1)
      }
      assert(f.killService.numKilled == 1)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      eventually {
        verify(f.queue, times(2)).addAsync(newApp, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      eventually {
        verify(f.queue, times(3)).addAsync(newApp, 1)
      }

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(4)
      }

      promise.future.futureValue

      // all remaining old tasks are killed
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)
      f.killService.killed should contain(instanceD.instanceId)

      verify(f.queue, times(3)).addAsync(newApp, 1)

      expectTerminated(ref)
    }

    "If all tasks are replaced already, the actor stops immediately" in {
      Given("An app without health checks and readiness checks, as well as 2 tasks of this version")
      val f = new Fixture
      val app = AppDefinition(id = "/myApp".toPath, instances = 2)
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB)
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)

      Then("The replace actor finishes immediately")
      expectTerminated(ref)
      promise.isCompleted should be(true)
    }

    "If all tasks are replaced already, we will wait for the readiness checks" in {
      Given("An app without health checks but readiness checks, as well as 1 task of this version")
      val f = new Fixture
      val check = ReadinessCheck()
      val port = PortDefinition(0, name = Some(check.portName))
      val app = AppDefinition(id = "/myApp".toPath, instances = 1, portDefinitions = Seq(port), readinessChecks = Seq(check))
      val instance = f.runningInstance(app)
      f.tracker.specInstancesSync(app.id) returns Seq(instance)
      val readyCheck = Observable.from(instance.tasksMap.values.map(task => ReadinessCheckResult(check.name, task.taskId, ready = true, None)))
      f.readinessCheckExecutor.execute(any[ReadinessCheckExecutor.ReadinessCheckSpec]) returns readyCheck
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)

      Then("It needs to wait for the readiness checks to pass")
      expectTerminated(ref)
      promise.isCompleted should be(true)
    }

    "If all tasks are replaced already, we will wait for the readiness checks and health checks" in {
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
      val readyCheck = Observable.from(instance.tasksMap.values.map(task => ReadinessCheckResult(ready.name, task.taskId, ready = true, None)))
      f.readinessCheckExecutor.execute(any[ReadinessCheckExecutor.ReadinessCheckSpec]) returns readyCheck
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)
      ref ! InstanceHealthChanged(instance.instanceId, app.version, app.id, healthy = Some(true))

      Then("It needs to wait for the readiness checks to pass")
      expectTerminated(ref)
      promise.isCompleted should be(true)
    }

    "Wait until the tasks are killed" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceB)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 5) returns Future.successful(Done)

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      for (_ <- 0 until newApp.instances)
        ref.receive(f.instanceChanged(newApp, Running))

      verify(f.queue, Mockito.timeout(1000)).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)

      promise.future.futureValue(Timeout(0.second))
      promise.isCompleted should be(true)
    }

    "Tasks to replace need to wait for health and readiness checks" in {
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

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.addAsync(newApp, 1) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }
      assert(f.killService.numKilled == 0)

      val newTaskId = Task.Id.forRunSpec(newApp.id)
      val newInstanceId = newTaskId.instanceId

      //unhealthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(false))
      eventually {
        f.killService.numKilled should be(0)
      }

      //unready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = false, None)
      eventually {
        f.killService.numKilled should be(0)
      }

      //healthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(true))
      eventually {
        f.killService.numKilled should be(0)
      }

      //ready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = true, None)
      eventually {
        f.killService.numKilled should be(1)
      }

      promise.future.futureValue
    }
  }
  class Fixture {
    val deploymentsManager: TestActorRef[Actor] = TestActorRef[Actor](Props.empty)
    val deploymentStatus = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    val killService = new KillServiceMock(system)
    val queue: LaunchQueue = mock[LaunchQueue]
    val tracker: InstanceTracker = mock[InstanceTracker]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val hostName = "host.some"
    val hostPorts = Seq(123)

    def runningInstance(app: AppDefinition): Instance = {
      TestInstanceBuilder.newBuilder(app.id, version = app.version)
        .addTaskWithBuilder().taskRunning().withNetworkInfo(hostName = Some(hostName), hostPorts = hostPorts).build()
        .getInstance()
    }

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      when(instance.instanceId).thenReturn(instanceId)
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = Some(healthy))
    }
    def replaceActor(app: AppDefinition, promise: Promise[Unit]): TestActorRef[TaskReplaceActor] = TestActorRef(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, killService, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
