package mesosphere.marathon.upgrade

import akka.actor.ActorRef
import akka.testkit.TestActorRef
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, UpgradeStrategy }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.upgrade.TaskReplaceActor.RetryKills
import mesosphere.marathon.{ MarathonTestHelper, TaskUpgradeCanceledException }
import org.apache.mesos.Protos.{ Status, TaskID }
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

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
    val app = AppDefinition(id = "myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID]
        val update = MesosStatusUpdateEvent("", Task.Id(taskId), "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", Task.Id(s"task_$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Replace with health checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0))

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID]
        val update = MesosStatusUpdateEvent("", Task.Id(taskId), "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref ! HealthStatusChanged(app.id, Task.Id(s"task_$i"), app.version, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Replace and scale down from more than new minCapacity") {
    val f = new Fixture
    val app = AppDefinition(id = "myApp".toPath, instances = 2, upgradeStrategy = UpgradeStrategy(1.0))
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID]
        val update = MesosStatusUpdateEvent("", Task.Id(taskId), "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()
    val ref = f.replaceActor(app, promise)
    watch(ref)

    eventually { verify(f.driver, times(2)).killTask(_) }
    eventually { app: AppDefinition => verify(f.queue, times(2)).add(app) }

    ref ! MesosStatusUpdateEvent("", Task.Id("task_1"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)
    ref ! MesosStatusUpdateEvent("", Task.Id("task_2"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)

    eventually { verify(f.driver, times(3)).killTask(_) }
    verify(f.queue).resetDelay(app)

    expectTerminated(ref)
  }

  test("Replace with minimum running tasks") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5)
    )

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")

    var oldTaskCount = 3

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      override def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID]
        val update = MesosStatusUpdateEvent("", Task.Id(taskId), "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // all new tasks are queued directly
    eventually { app: AppDefinition => verify(f.queue, times(3)).add(app) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(oldTaskCount == 2)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id(s"task_0"), app.version, alive = true)
    eventually { oldTaskCount should be(1) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id(s"task_1"), app.version, alive = true)
    eventually { oldTaskCount should be(0) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, Task.Id(s"task_2"), app.version, alive = true)
    oldTaskCount should be(0)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)
    verify(f.driver).killTask(taskC.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade without over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5, 0.0)
    )

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")

    var oldTaskCount = 3

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = Task.Id(invocation.getArguments()(0).asInstanceOf[TaskID])
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(oldTaskCount == 2)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_0"), app.version, alive = true)
    eventually { oldTaskCount should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_1"), app.version, alive = true)
    eventually { oldTaskCount should be(0) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, Task.Id("task_2"), app.version, alive = true)
    oldTaskCount should be(0)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.queue).resetDelay(app)
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)
    verify(f.driver).killTask(taskC.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with minimal over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.0) // 1 task over-capacity is ok
    )

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")

    var oldTaskCount = 3

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = Task.Id(invocation.getArguments()(0).asInstanceOf[TaskID])
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // only one task is queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }
    assert(oldTaskCount == 3)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_0"), app.version, alive = true)
    eventually { oldTaskCount should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_1"), app.version, alive = true)
    eventually { oldTaskCount should be(1) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_2"), app.version, alive = true)
    eventually { oldTaskCount should be(0) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)
    verify(f.driver).killTask(taskC.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with 2/3 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.7)
    )

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")

    var oldTaskCount = 3

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = Task.Id(invocation.getArguments()(0).asInstanceOf[TaskID])
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // two tasks are queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(f.queue)
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 2) }
    assert(oldTaskCount == 3)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_0"), app.version, alive = true)
    eventually { oldTaskCount should be(2) }
    eventually { queueOrder.verify(f.queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_1"), app.version, alive = true)
    eventually { oldTaskCount should be(1) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_2"), app.version, alive = true)
    eventually { oldTaskCount should be(0) }
    queueOrder.verify(f.queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver).killTask(taskB.launchedMesosId.get)
    verify(f.driver).killTask(taskC.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Downscale with rolling upgrade with 1 over-capacity") {
    val f = new Fixture
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1.0, maximumOverCapacity = 0.3)
    )

    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val taskC = MarathonTestHelper.runningTask("taskC_id")
    val taskD = MarathonTestHelper.runningTask("taskD_id")

    var oldTaskCount = 4

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB, taskC, taskD))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = Task.Id(invocation.getArguments()(0).asInstanceOf[TaskID])
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    // one task is killed directly because we are over capacity
    val order = org.mockito.Mockito.inOrder(f.queue, f.driver)
    order.verify(f.driver).killTask(taskA.launchedMesosId.get)

    // the kill is confirmed (see answer above) and the first new task is queued
    eventually { order.verify(f.queue).add(app, 1) }
    assert(oldTaskCount == 3)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_0"), app.version, alive = true)
    eventually { oldTaskCount should be(2) }
    eventually { order.verify(f.queue).add(app, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_1"), app.version, alive = true)
    eventually { oldTaskCount should be(1) }
    eventually { order.verify(f.queue).add(app, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, Task.Id("task_2"), app.version, alive = true)
    eventually { oldTaskCount should be(0) }
    eventually { order.verify(f.queue, never()).add(app, 1) }

    Await.result(promise.future, 5.seconds)

    // all remaining old tasks are killed
    verify(f.driver).killTask(taskB.launchedMesosId.get)
    verify(f.driver).killTask(taskC.launchedMesosId.get)
    verify(f.driver).killTask(taskD.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Cancelled") {
    val f = new Fixture
    val app = AppDefinition(id = "myApp".toPath, instances = 2)
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

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

  test("Retry outstanding kills") {
    val f = new Fixture
    val app = AppDefinition(id = "myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))
    when(f.driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      var firstKillForTaskB = true

      def answer(invocation: InvocationOnMock): Status = {
        val taskId = Task.Id(invocation.getArguments()(0).asInstanceOf[TaskID])

        if (taskId == taskB.taskId && firstKillForTaskB) {
          firstKillForTaskB = false
        }
        else {
          val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString)
          system.eventStream.publish(update)
        }
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    ref.underlyingActor.periodicalRetryKills.cancel()
    ref ! RetryKills

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", Task.Id(s"task_$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(f.queue).resetDelay(app)
    verify(f.driver).killTask(taskA.launchedMesosId.get)
    verify(f.driver, times(2)).killTask(taskB.launchedMesosId.get)

    expectTerminated(ref)
  }

  test("Wait until the tasks are killed") {
    val f = new Fixture
    val app = AppDefinition(id = "myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    when(f.tracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = f.replaceActor(app, promise)
    watch(ref)

    for (i <- 0 until app.instances)
      ref.receive(MesosStatusUpdateEvent("", Task.Id(s"task_$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

    verify(f.queue, Mockito.timeout(1000)).resetDelay(app)
    verify(f.driver, Mockito.timeout(1000)).killTask(taskA.launchedMesosId.get)
    verify(f.driver, Mockito.timeout(1000)).killTask(taskB.launchedMesosId.get)

    promise.isCompleted should be(false)

    for (oldTask <- Iterable(taskA, taskB))
      ref.receive(MesosStatusUpdateEvent("", oldTask.taskId, "TASK_KILLED", "", app.id, "", None, Nil, app.version.toString))

    Await.result(promise.future, 0.second)
  }

  class Fixture {
    val deploymentsManager = mock[ActorRef]
    val deploymentStatus = mock[DeploymentStatus]
    val driver = mock[SchedulerDriver]
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    def replaceActor(app: AppDefinition, promise: Promise[Unit]): TestActorRef[TaskReplaceActor] = TestActorRef(
      TaskReplaceActor.props(deploymentsManager, deploymentStatus, driver, queue,
        tracker, system.eventStream, readinessCheckExecutor, app, promise)
    )
  }
}
