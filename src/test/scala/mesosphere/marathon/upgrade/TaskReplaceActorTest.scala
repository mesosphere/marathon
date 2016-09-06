package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.testkit.TestActorRef
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheckResult, ReadinessCheck, ReadinessCheckExecutor }
import mesosphere.marathon.core.task.{ Task, TaskKillServiceMock }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.core.health.HealthCheck
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

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
import scala.collection.immutable.Seq

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
    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", Task.Id.forRunSpec(app.id), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)

    expectTerminated(ref)
  }

  test("Replace with health checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0))

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)

    expectTerminated(ref)
  }

  test("Replace and scale down from more than new minCapacity") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 2, upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0))
    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    eventually { f.killService.numKilled should be (1) }

    ref ! MesosStatusUpdateEvent("", Task.Id.forRunSpec(app.id), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)
    eventually { f.killService.numKilled should be (2) }

    ref ! MesosStatusUpdateEvent("", Task.Id.forRunSpec(app.id), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)
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
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5)
    )

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // all new tasks are queued directly
    eventually { app: AppDefinition => verify(f.queue, times(3)).add(app) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(2) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(3) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    f.killService.numKilled should be(3)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)
    f.killService.killed should contain (taskC.taskId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade without over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5, 0.0)
    )

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(3) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    f.killService.numKilled should be(3)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.queue).resetDelay(app)
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)
    f.killService.killed should contain (taskC.taskId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with minimal over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.0) // 1 task over-capacity is ok
    )

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }
    assert(f.killService.numKilled == 0)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(3) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)
    f.killService.killed should contain (taskC.taskId)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with 2/3 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.7)
    )

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // two tasks are queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 2) }
    assert(f.killService.numKilled == 0)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(2) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(3) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)
    f.killService.killed should contain (taskC.taskId)

    expectTerminated(ref)
  }

  test("Downscale with rolling upgrade with 1 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0, maximumOverCapacity = 0.3)
    )

    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskC = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskD = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC, taskD))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // one task is killed directly because we are over capacity
    val order = org.mockito.Mockito.inOrder(f.queue)
    f.killService.killed should contain (taskA.taskId)

    // the kill is confirmed (see answer above) and the first new task is queued
    eventually { order.verify(f.queue).add(app, 1) }
    assert(f.killService.numKilled == 1)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(2) }
    eventually { order.verify(f.queue).add(app, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(3) }
    eventually { order.verify(f.queue).add(app, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id.forRunSpec(app.id), app.version, alive = true)
    eventually { f.killService.numKilled should be(4) }
    eventually { order.verify(f.queue, never()).add(app, 1) }

    Await.result(promise.future, 5.seconds)

    // all remaining old tasks are killed
    f.killService.killed should contain (taskB.taskId)
    f.killService.killed should contain (taskC.taskId)
    f.killService.killed should contain (taskD.taskId)

    expectTerminated(ref)
  }

  test("Cancelled") {
    val f = new Fixture
    val app = AppDefinition(id = "/myApp".toPath, instances = 2)
    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))

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
    val taskA = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)
    val taskB = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref.receive(MesosStatusUpdateEvent("", Task.Id.forRunSpec(app.id), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

    verify(f.queue, Mockito.timeout(1000)).resetDelay(app)
    f.killService.killed should contain (taskA.taskId)
    f.killService.killed should contain (taskB.taskId)

    Await.result(promise.future, 0.second)
    promise.isCompleted should be(true)
  }

  test("Tasks to replace need to wait for health and readiness checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "/myApp".toPath,
      instances = 1,
      healthChecks = Set(HealthCheck()),
      readinessChecks = Seq(ReadinessCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 1.0)
    )

    val task = MarathonTestHelper.runningTask(Task.Id.forRunSpec(app.id).idString)

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(task))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }
    assert(f.killService.numKilled == 0)

    val newTaskId = Task.Id.forRunSpec(app.id)

    //unhealthy
    ref ! HealthStatusChanged(app.id, newTaskId, app.version, alive = false)
    eventually { f.killService.numKilled should be(0) }

    //unready
    ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = false, None)
    eventually { f.killService.numKilled should be(0) }

    //healthy
    ref ! HealthStatusChanged(app.id, newTaskId, app.version, alive = true)
    eventually { f.killService.numKilled should be(0) }

    //ready
    ref ! ReadinessCheckResult(ReadinessCheck.DefaultName, newTaskId, ready = true, None)
    eventually { f.killService.numKilled should be(1) }

    Await.result(promise.future, 5.seconds)
  }

  class Fixture {
    val deploymentsManager = TestActorRef(Props.empty)
    val deploymentStatus = DeploymentStatus(DeploymentPlan.empty, DeploymentStep(Seq.empty))
    private[this] val driver = mock[SchedulerDriver]
    val killService = new TaskKillServiceMock(system)
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def replaceActor(app: AppDefinition, promise: Promise[Unit]): TestActorRef[TaskReplaceActor] = TestActorRef(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, driver, killService, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
