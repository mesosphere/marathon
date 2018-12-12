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
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.{KillServiceMock, Task}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.util.CancellableOnce
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Future, Promise}

class TaskReplaceActorTest extends AkkaUnitTest with Eventually {
  "TaskReplaceActor" should {
    "replace old tasks without health checks" in {
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

      promise.future.futureValue
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)

      expectTerminated(ref)
    }

    "not kill new and already started tasks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))

      val instanceA = f.runningInstance(app)

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 4) returns Future.successful(Done)

      val instanceC = f.runningInstance(newApp)

      f.tracker.specInstancesSync(app.id) returns Seq(instanceA, instanceC)
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // Report all remaining instances as running.
      for (_ <- 0 until (newApp.instances - 1))
        ref ! f.instanceChanged(newApp, Running)

      promise.future.futureValue
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should not contain instanceC.instanceId

      expectTerminated(ref)
    }

    "replace old tasks with health checks" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 5) returns Future.successful(Done)

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

    "replace and scale down from more than new minCapacity" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 1) returns Future.successful(Done)

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
      eventually { app: AppDefinition => verify(f.queue, times(2)).add(app) }

      promise.future.futureValue

      eventually {
        f.killService.numKilled should be(3)
      }
      verify(f.queue).resetDelay(newApp)

      expectTerminated(ref)
    }

    "replace tasks with minimum running number of tasks" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 3) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // all new tasks are queued directly
      eventually { app: AppDefinition => verify(f.queue, times(3)).add(app) }

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      eventually{
        f.killService.numKilled should be(1)
      }

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

    "replace tasks during rolling upgrade *without* over-capacity" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

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

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      eventually {
        f.killService.numKilled should be(1)
      }

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      // second new task becomes healthy and the last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      // third new task becomes healthy
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }

      promise.future.futureValue

      // all old tasks are killed
      verify(f.queue).resetDelay(newApp)
      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
      f.killService.killed should contain(instanceC.instanceId)

      expectTerminated(ref)
    }

    "replace tasks during rolling upgrade *with* minimal over-capacity" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(newApp, 1) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly, all old still running
      val queueOrder = org.mockito.Mockito.inOrder(f.queue)
      eventually {
        queueOrder.verify(f.queue).add(_: AppDefinition, 1)
      }

      eventually {
        f.killService.numKilled should be(0)
      }

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

    "replace tasks during rolling upgrade with 2/3 over-capacity" in {
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
      f.tracker.get(instanceA.instanceId) returns Future.successful(Some(instanceA))
      f.tracker.get(instanceB.instanceId) returns Future.successful(Some(instanceB))
      f.tracker.get(instanceC.instanceId) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.queue.add(eq(newApp), any) returns Future.successful(Done)
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

    "downscale tasks during rolling upgrade with 1 over-capacity" in {
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
        f.killService.killed should contain(instanceA.instanceId)
      }

      // the kill is confirmed (see answer above) and the first new task is queued
      eventually {
        verify(f.queue, times(1)).resetDelay(newApp)
      }

      eventually {
        f.killService.numKilled should be(1)
      }

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(2)
      }
      eventually {
        verify(f.queue, times(2)).add(newApp, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        f.killService.numKilled should be(3)
      }
      eventually {
        verify(f.queue, times(3)).add(newApp, 1)
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

      verify(f.queue, times(3)).add(newApp, 1)

      expectTerminated(ref)
    }

    "stop the actor if all tasks are replaced already" in {
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

    "wait for readiness checks if all tasks are replaced already" in {
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

    " wait for the readiness checks and health checks if all tasks are replaced already" in {
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

    "wait until the tasks are killed" in {
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

      f.killService.killed should contain(instanceA.instanceId)
      f.killService.killed should contain(instanceB.instanceId)
    }

    "wait for health and readiness checks for new tasks" in {
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
      assert(f.killService.numKilled == 0)

      val newInstanceId = Instance.Id.forRunSpec(newApp.id)
      val newTaskId = Task.Id(newInstanceId)

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

    tracker.setGoal(any, any) returns Future.successful(Done)

    def runningInstance(app: AppDefinition): Instance = {
      TestInstanceBuilder.newBuilder(app.id, version = app.version)
        .addTaskWithBuilder().taskRunning().withNetworkInfo(hostName = Some(hostName), hostPorts = hostPorts).build()
        .getInstance()
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
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, killService, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
