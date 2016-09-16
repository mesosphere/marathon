package mesosphere.marathon.upgrade

import akka.actor.{ Actor, Props }
import akka.testkit.TestActorRef
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.MarathonHttpHealthCheck
import mesosphere.marathon.core.instance.InstanceStatus.Running
import mesosphere.marathon.core.instance.{ InstanceStatus, Instance }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheck, ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.{ Task, KillServiceMock }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, UpgradeStrategy }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{ MarathonTestHelper, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskReplaceActorTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with Eventually
    with BeforeAndAfterAll
    with MockitoSugar {

  test("Replace without health checks") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! f.instanceChanged(app, Running)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)

    expectTerminated(ref)
  }

  test("Replace with health checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 5,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(0.0))

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! f.healthChanged(app, healthy = true)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)

    expectTerminated(ref)
  }

  test("Replace and scale down from more than new minCapacity") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 2, upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0))
    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    eventually { f.killService.numKilled should be (1) }

    ref ! f.instanceChanged(app, Running)
    eventually { f.killService.numKilled should be (2) }

    ref ! f.instanceChanged(app, Running)
    eventually { app: AppDefinition => verify(f.queue, times(2)).add(app) }

    Await.result(promise.future, 5.seconds)

    eventually { f.killService.numKilled should be (3) }
    verify(f.queue).resetDelay(app)

    expectTerminated(ref)
  }

  test("Replace with minimum running tasks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(0.5)
    )

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // all new tasks are queued directly
    eventually { app: AppDefinition => verify(f.queue, times(3)).add(app) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(2) }

    // second new task becomes healthy and the last old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(3) }

    // third new task becomes healthy
    ref ! f.healthChanged(app, healthy = true)
    f.killService.numKilled should be(3)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)
    f.killService.killed should contain (instanceC.instanceId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade without over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(0.5, 0.0)
    )

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and the last old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(3) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy
    ref ! f.healthChanged(app, healthy = true)
    f.killService.numKilled should be(3)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)
    f.killService.killed should contain (instanceC.instanceId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with minimal over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(1.0, 0.0) // 1 task over-capacity is ok
    )

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }
    assert(f.killService.numKilled == 0)

    // first new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(3) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)
    f.killService.killed should contain (instanceC.instanceId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with 2/3 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(1.0, 0.7)
    )

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // two tasks are queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 2) }
    assert(f.killService.numKilled == 0)

    // first new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(2) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    // third new task becomes healthy and last old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(3) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)
    f.killService.killed should contain (instanceC.instanceId)

    expectTerminated(ref)
  }

  test("Downscale with rolling upgrade with 1 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(0))),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0, maximumOverCapacity = 0.3)
    )

    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)
    val instanceC = f.runningInstance(app)
    val instanceD = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB, instanceC, instanceD))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // one task is killed directly because we are over capacity
    val order = org.mockito.Mockito.inOrder(f.queue)
    f.killService.killed should contain (instanceA.instanceId)

    // the kill is confirmed (see answer above) and the first new task is queued
    eventually { order.verify(f.queue).add(app, 1) }
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { order.verify(f.queue).add(app, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(3) }
    eventually { order.verify(f.queue).add(app, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! f.healthChanged(app, healthy = true)
    eventually { f.killService.numKilled should be(4) }
    eventually { order.verify(f.queue, never()).add(app, 1) }

    Await.result(promise.future, 5.seconds)

    // all remaining old tasks are killed
    f.killService.killed should contain (instanceB.instanceId)
    f.killService.killed should contain (instanceC.instanceId)
    f.killService.killed should contain (instanceD.instanceId)

    expectTerminated(ref)
  }

  test("Cancelled") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 2)
    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Wait until the tasks are killed") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val instanceA = f.runningInstance(app)
    val instanceB = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instanceA, instanceB))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref.receive(f.instanceChanged(app, Running))

    verify(f.queue, Mockito.timeout(1000)).resetDelay(app)
    f.killService.killed should contain (instanceA.instanceId)
    f.killService.killed should contain (instanceB.instanceId)

    Await.result(promise.future, 0.second)
    promise.isCompleted should be(true)
  }

  test("Tasks to replace need to wait for health and readiness checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 1,
      healthChecks = Set(MarathonHttpHealthCheck()),
      readinessChecks = Seq(ReadinessCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 1.0)
    )

    val instance = f.runningInstance(app)

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Iterable(instance))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }
    assert(f.killService.numKilled == 0)

    val newTaskId = Task.Id.forRunSpec(app.id)
    val newInstanceId = newTaskId.instanceId

    //unhealthy
    ref ! InstanceHealthChanged(newInstanceId, app.version, app.id, healthy = Some(false))
    eventually { f.killService.numKilled should be(0) }

    //unready
    ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = false, None)
    eventually { f.killService.numKilled should be(0) }

    //healthy
    ref ! InstanceHealthChanged(newInstanceId, app.version, app.id, healthy = Some(true))
    eventually { f.killService.numKilled should be(0) }

    //ready
    ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = true, None)
    eventually { f.killService.numKilled should be(1) }

    Await.result(promise.future, 5.seconds)
  }

  class Fixture {
    val deploymentsManager = TestActorRef[Actor](Props.empty)
    val deploymentStatus = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    private[this] val driver = mock[SchedulerDriver]
    val killService = new KillServiceMock(system)
    val queue = mock[LaunchQueue]
    val tracker = mock[InstanceTracker]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def runningInstance(app: AppDefinition): Instance = {
      Instance(MarathonTestHelper.runningTaskForApp(app.id))
    }

    def instanceChanged(app: AppDefinition, status: InstanceStatus): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      when(instance.instanceId).thenReturn(instanceId)
      InstanceChanged(instanceId, app.version, app.id, status, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = Some(healthy))
    }
    def replaceActor(app: AppDefinition, promise: Promise[Unit]): TestActorRef[TaskReplaceActor] = TestActorRef(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, driver, killService, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
