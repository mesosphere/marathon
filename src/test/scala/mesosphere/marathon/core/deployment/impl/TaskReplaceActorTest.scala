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
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.readiness.{ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.termination.KillReason
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.util.CancellableOnce
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Future, Promise}

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      ref ! f.instanceKilled(instanceA)
      ref ! f.instanceKilled(instanceB)

      for (_ <- 0 until newApp.instances)
        ref ! f.instanceChanged(newApp, Running)

      promise.future.futureValue

      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceA), any)(any)
        verify(f.scheduler).decommission(eq[Instance](instanceB), any)(any)
      }

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

      val instanceC = f.runningInstance(newApp)

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      ref ! f.instanceKilled(instanceA)

      // Report all remaining instances as running.
      for (_ <- 0 until (newApp.instances - 1))
        ref ! f.instanceChanged(newApp, Running)

      promise.future.futureValue

      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceA), any)(any)
      }

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      ref ! f.instanceKilled(instanceA)
      ref ! f.instanceKilled(instanceB)

      for (_ <- 0 until newApp.instances)
        ref ! f.healthChanged(newApp, healthy = true)

      promise.future.futureValue
      verify(f.scheduler).resetDelay(newApp)
      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceA), any)(any)
        verify(f.scheduler).decommission(eq[Instance](instanceB), any)(any)
      }

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      ref ! f.instanceChanged(newApp, Running)
      eventually {
        verify(f.scheduler, times(2)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      ref ! f.instanceChanged(newApp, Running)
      eventually { app: AppDefinition => verify(f.scheduler, times(2)).schedule(eq(app), eq(1))(any) }

      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      verify(f.scheduler).resetDelay(newApp)

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // all new tasks are scheduled directly
      eventually { app: AppDefinition => verify(f.scheduler, times(3)).schedule(eq(app), eq(1))(any) }

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      eventually{
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(2)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      // second new task becomes healthy and the last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      // third new task becomes healthy
      ref ! f.healthChanged(newApp, healthy = true)

      promise.future.futureValue

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly
      val queueOrder = org.mockito.Mockito.inOrder(f.scheduler)
      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // ceiling(minimumHealthCapacity * 3) = 2 are left running
      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(2)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // second new task becomes healthy and the last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // third new task becomes healthy
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }

      promise.future.futureValue

      // all old tasks are killed
      verify(f.scheduler).resetDelay(newApp)

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly, all old still running
      val queueOrder = org.mockito.Mockito.inOrder(f.scheduler)
      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(2)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      queueOrder.verify(f.scheduler, never).schedule(_: AppDefinition, 1)

      promise.future.futureValue

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      f.scheduler.schedule(eq(newApp), any)(any) returns Future.successful(Done)
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // two tasks are queued directly, all old still running
      val queueOrder = org.mockito.Mockito.inOrder(f.scheduler)
      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 2)
      }
      verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(2)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      queueOrder.verify(f.scheduler, never).schedule(_: AppDefinition, 1)

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler, times(3)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      queueOrder.verify(f.scheduler, never).schedule(_: AppDefinition, 1)

      promise.future.futureValue

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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB, instanceC, instanceD))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      f.scheduler.getInstance(eq(instanceC.instanceId))(any) returns Future.successful(Some(instanceC))
      f.scheduler.getInstance(eq(instanceD.instanceId))(any) returns Future.successful(Some(instanceD))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // one task is killed directly because we are over capacity
      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceA), any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceA)

      // the kill is confirmed (see answer above) and the first new task is queued
      eventually {
        verify(f.scheduler, times(1)).sync(eq(newApp))(any)
        verify(f.scheduler, times(1)).resetDelay(newApp)
      }

      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }

      // first new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceB), any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceB)

      eventually {
        verify(f.scheduler, times(2)).schedule(eq(newApp), eq(1))(any)
      }

      // second new task becomes healthy and another old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceC), any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceC)

      eventually {
        verify(f.scheduler, times(3)).schedule(eq(newApp), eq(1))(any)
      }

      // third new task becomes healthy and last old task is killed
      ref ! f.healthChanged(newApp, healthy = true)
      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceD), any[KillReason])(any)
      }
      ref ! f.instanceKilled(instanceD)

      promise.future.futureValue

      verify(f.scheduler, times(3)).schedule(eq(newApp), eq(1))(any)

      expectTerminated(ref)
    }

    "If all tasks are replaced already, the actor stops immediately" in {
      Given("An app without health checks and readiness checks, as well as 2 tasks of this version")
      val f = new Fixture
      val app = AppDefinition(id = "/myApp".toPath, instances = 2)
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)
      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))
      val promise = Promise[Unit]()

      When("The replace actor is started")
      val ref = f.replaceActor(app, promise)
      watch(ref)

      Then("The replace actor finishes immediately")
      expectTerminated(ref)
      promise.future.futureValue
    }

    "If all tasks are replaced already, we will wait for the readiness checks" in {
      Given("An app without health checks but readiness checks, as well as 1 task of this version")
      val f = new Fixture
      val check = ReadinessCheck()
      val port = PortDefinition(0, name = Some(check.portName))
      val app = AppDefinition(id = "/myApp".toPath, instances = 1, portDefinitions = Seq(port), readinessChecks = Seq(check))
      val instance = f.runningInstance(app)
      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instance))
      f.scheduler.getInstance(eq(instance.instanceId))(any) returns Future.successful(Some(instance))
      val (_, readyCheck) = f.readinessResults(instance, check.name, ready = true)
      f.readinessCheckExecutor.execute(any[ReadinessCheckExecutor.ReadinessCheckSpec]) returns readyCheck
      val promise = Promise[Unit]()

      When("The replace actor is started")
      f.replaceActor(app, promise)

      Then("It needs to wait for the readiness checks to pass")
      promise.future.futureValue
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
      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instance))
      f.scheduler.getInstance(eq(instance.instanceId))(any) returns Future.successful(Some(instance))
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

    "Wait until the tasks are killed" in {
      val f = new Fixture
      val app = AppDefinition(
        id = "/myApp".toPath,
        instances = 5,
        versionInfo = VersionInfo.forNewConfig(Timestamp(0)),
        upgradeStrategy = UpgradeStrategy(0.0))
      val instanceA = f.runningInstance(app)
      val instanceB = f.runningInstance(app)

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instanceA, instanceB))
      f.scheduler.getInstance(eq(instanceA.instanceId))(any) returns Future.successful(Some(instanceA))
      f.scheduler.getInstance(eq(instanceB.instanceId))(any) returns Future.successful(Some(instanceB))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      ref ! f.instanceKilled(instanceA)
      ref ! f.instanceKilled(instanceB)

      for (_ <- 0 until newApp.instances)
        ref ! f.instanceChanged(newApp, Running)

      eventually {
        verify(f.scheduler).decommission(eq[Instance](instanceA), any[KillReason])(any)
        verify(f.scheduler).decommission(eq[Instance](instanceB), any[KillReason])(any)
      }

      verify(f.scheduler, timeout(1000)).resetDelay(newApp)

      promise.future.futureValue
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

      f.scheduler.getInstances(eq(app.id))(any) returns Future.successful(Seq(instance))
      f.scheduler.getInstance(eq(instance.instanceId))(any) returns Future.successful(Some(instance))

      val promise = Promise[Unit]()
      val newApp = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))
      val ref = f.replaceActor(newApp, promise)
      watch(ref)

      // only one task is queued directly
      val queueOrder = org.mockito.Mockito.inOrder(f.scheduler)
      eventually {
        queueOrder.verify(f.scheduler).schedule(_: AppDefinition, 1)
      }
      verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)

      val newInstanceId = Instance.Id.forRunSpec(newApp.id)
      val newTaskId = Task.Id.forInstanceId(newInstanceId)

      //unhealthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(false))
      eventually {
        verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)
      }

      //unready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = false, None)
      eventually {
        verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)
      }

      //healthy
      ref ! InstanceHealthChanged(newInstanceId, newApp.version, newApp.id, healthy = Some(true))
      eventually {
        verify(f.scheduler, times(0)).decommission(any[Instance], any[KillReason])(any)
      }

      //ready
      ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = true, None)
      eventually {
        verify(f.scheduler, times(1)).decommission(any[Instance], any[KillReason])(any)
      }
      ref ! f.instanceKilled(instance)

      promise.future.futureValue
    }
  }
  class Fixture {
    val deploymentsManager: TestActorRef[Actor] = TestActorRef[Actor](Props.empty)
    val deploymentStatus = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    val scheduler: scheduling.Scheduler = mock[scheduling.Scheduler]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val hostName = "host.some"
    val hostPorts = Seq(123)

    scheduler.stop(any[Instance], any)(any) returns Future.successful(Done)
    scheduler.decommission(any[Instance], any)(any) returns Future.successful(Done)
    scheduler.schedule(any, any)(any) returns Future.successful(Done)
    scheduler.reschedule(any[Seq[Instance]], any)(any) returns Future.successful(Done)
    scheduler.sync(any)(any) returns Future.successful(Done)

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
      val instance: Instance = mock[Instance]
      when(instance.instanceId).thenReturn(instanceId)
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def instanceKilled(instance: Instance): InstanceChanged = {
      val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed))
      InstanceChanged(instance.instanceId, instance.runSpecVersion, instance.runSpecId, Condition.Killed, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = Some(healthy))
    }

    def replaceActor(app: AppDefinition, promise: Promise[Unit]): ActorRef = system.actorOf(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, scheduler, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
