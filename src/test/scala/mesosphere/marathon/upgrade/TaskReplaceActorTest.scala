package mesosphere.marathon.upgrade

import akka.actor.ActorSystem
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheckActor.AppHealth
import mesosphere.marathon.health.{ Health, HealthCheckManager, HealthCheck }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Timestamp, AppDefinition, UpgradeStrategy }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.TaskReplaceActor.RetryKills
import org.apache.mesos.Protos.{ Status, TaskID }
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await, Promise }

class TaskReplaceActorTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with Eventually
    with BeforeAndAfterAll
    with MockitoSugar {

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Replace without health checks") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(id = id, instances = 5, upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", s"task_$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(queue).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Replace with health checks") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    for (i <- 0 until app.instances)
      ref ! HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(queue).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Replace and scale down from more than new minCapacity") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(id = id, instances = 2, upgradeStrategy = UpgradeStrategy(1.0),
      versionInfo = versionInfo)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val taskC = MarathonTask.newBuilder().setId("taskC_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskC))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    eventually { verify(driver, times(2)).killTask(_) }
    eventually { app: AppDefinition => verify(queue, times(2)).add(app) }

    ref ! MesosStatusUpdateEvent("", "task_1", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)
    ref ! MesosStatusUpdateEvent("", "task_2", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)

    eventually { verify(driver, times(3)).killTask(_) }
    verify(queue).resetDelay(app)

    expectTerminated(ref)
  }

  test("Replace with minimum running tasks") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5),
      versionInfo = versionInfo
    )

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val taskC = MarathonTask.newBuilder().setId("taskC_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    var oldTaskCount = 3

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskC))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      override def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    // all new tasks are queued directly
    eventually { app: AppDefinition => verify(queue, times(3)).add(app) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(oldTaskCount == 2)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_0", app.version.toString, alive = true)
    eventually { oldTaskCount should be(1) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, s"task_1", app.version.toString, alive = true)
    eventually { oldTaskCount should be(0) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, s"task_2", app.version.toString, alive = true)
    oldTaskCount should be(0)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskC.getId).build())

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade without over-capacity") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.5, 0.0),
      versionInfo = versionInfo
    )

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val taskC = MarathonTask.newBuilder().setId("taskC_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    var oldTaskCount = 3

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskC))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    // only one task is queued directly
    val queueOrder = org.mockito.Mockito.inOrder(queue);
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // ceiling(minimumHealthCapacity * 3) = 2 are left running
    assert(oldTaskCount == 2)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_0", app.version.toString, alive = true)
    eventually { oldTaskCount should be(1) }
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and the last old task is killed
    ref ! HealthStatusChanged(app.id, s"task_1", app.version.toString, alive = true)
    eventually { oldTaskCount should be(0) }
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy
    ref ! HealthStatusChanged(app.id, s"task_2", app.version.toString, alive = true)
    oldTaskCount should be(0)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(queue).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskC.getId).build())

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with minimal over-capacity") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.0), // 1 task over-capacity is ok
      versionInfo = versionInfo
    )

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val taskC = MarathonTask.newBuilder().setId("taskC_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    var oldTaskCount = 3

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskC))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    // only one task is queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(queue);
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }
    assert(oldTaskCount == 3)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_0", app.version.toString, alive = true)
    eventually { oldTaskCount should be(2) }
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_1", app.version.toString, alive = true)
    eventually { oldTaskCount should be(1) }
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, s"task_2", app.version.toString, alive = true)
    eventually { oldTaskCount should be(0) }
    queueOrder.verify(queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskC.getId).build())

    expectTerminated(ref)
  }

  test("Replace with rolling upgrade with 2/3 over-capacity") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 3,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0, 0.7),
      versionInfo = versionInfo
    )

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val taskC = MarathonTask.newBuilder().setId("taskC_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    var oldTaskCount = 3

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskC))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)

        oldTaskCount -= 1
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    // two tasks are queued directly, all old still running
    val queueOrder = org.mockito.Mockito.inOrder(queue);
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 2) }
    assert(oldTaskCount == 3)

    // first new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_0", app.version.toString, alive = true)
    eventually { oldTaskCount should be(2) }
    eventually { queueOrder.verify(queue).add(_: AppDefinition, 1) }

    // second new task becomes healthy and another old task is killed
    ref ! HealthStatusChanged(app.id, s"task_1", app.version.toString, alive = true)
    eventually { oldTaskCount should be(1) }
    queueOrder.verify(queue, never()).add(_: AppDefinition, 1)

    // third new task becomes healthy and last old task is killed
    ref ! HealthStatusChanged(app.id, s"task_2", app.version.toString, alive = true)
    eventually { oldTaskCount should be(0) }
    queueOrder.verify(queue, never()).add(_: AppDefinition, 1)

    Await.result(promise.future, 5.seconds)

    // all old tasks are killed
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskC.getId).build())

    expectTerminated(ref)
  }

  test("Resume replace after failover") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").setVersion(versionInfo.version.toString).build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").setVersion(versionInfo.version.toString).build()
    val taskOld = MarathonTask.newBuilder().setId("taskOld_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]
    val health1 = mock[Health]
    val health2 = mock[Health]

    when(health1.alive).thenReturn(false)
    when(health2.alive).thenReturn(false)
    when(health1.taskId).thenReturn("taskA_id")
    when(health2.taskId).thenReturn("taskB_id")
    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskOld))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! AppHealth(collection.immutable.Seq(health1, health2))

    for (i <- 0 until app.instances)
      ref ! HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(queue, never()).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskOld.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Resume replace after failover with some healthy") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").setVersion(versionInfo.version.toString).build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").setVersion(versionInfo.version.toString).build()
    val taskOld = MarathonTask.newBuilder().setId("taskOld_id").setVersion("1970-01-01T00:00:00.000Z").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]
    val health1 = mock[Health]
    val health2 = mock[Health]

    when(health1.alive).thenReturn(true)
    when(health2.alive).thenReturn(true)
    when(health1.taskId).thenReturn("taskA_id")
    when(health2.taskId).thenReturn("taskB_id")
    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskOld))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! AppHealth(collection.immutable.Seq(health1, health2))

    for (i <- 0 until app.instances - 2)
      ref ! HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(queue, never()).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskOld.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Resume replace after failover minhealth 1") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 2,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").setVersion(versionInfo.version.toString).build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").setVersion(versionInfo.version.toString).build()
    val taskOld = MarathonTask.newBuilder().setId("taskOld_id").setVersion("1970-01-01T00:00:00.000Z").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]
    val health1 = mock[Health]
    val health2 = mock[Health]

    when(health1.alive).thenReturn(true)
    when(health2.alive).thenReturn(true)
    when(health1.taskId).thenReturn("taskA_id")
    when(health2.taskId).thenReturn("taskB_id")
    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskOld))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! AppHealth(collection.immutable.Seq(health1, health2))

    Await.result(promise.future, 5.seconds)
    verify(queue, never()).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskOld.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Resume replace only kill") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 2,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").setVersion(versionInfo.version.toString).build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").setVersion(versionInfo.version.toString).build()
    val taskOld = MarathonTask.newBuilder().setId("taskOld_id").setVersion("1970-01-01T00:00:00.000Z").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]
    val health1 = mock[Health]
    val health2 = mock[Health]

    when(health1.alive).thenReturn(true)
    when(health2.alive).thenReturn(true)
    when(health1.taskId).thenReturn("taskA_id")
    when(health2.taskId).thenReturn("taskB_id")
    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB, taskOld))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue
        val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
        system.eventStream.publish(update)
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! AppHealth(collection.immutable.Seq(health1, health2))

    Await.result(promise.future, 5.seconds)
    verify(queue, never()).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskOld.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Resume replace noop") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(
      id = id,
      instances = 2,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").setVersion(versionInfo.version.toString).build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").setVersion(versionInfo.version.toString).build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]
    val health1 = mock[Health]
    val health2 = mock[Health]

    when(health1.alive).thenReturn(true)
    when(health2.alive).thenReturn(true)
    when(health1.taskId).thenReturn("taskA_id")
    when(health2.taskId).thenReturn("taskB_id")
    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! AppHealth(collection.immutable.Seq(health1, health2))

    Await.result(promise.future, 5.seconds)
    verify(queue, never()).resetDelay(app)
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, never()).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(id = id, instances = 2, versionInfo = versionInfo)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Retry outstanding kills") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(id = id, instances = 5, upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      var firstKillForTaskB = true

      def answer(invocation: InvocationOnMock): Status = {
        val taskId = invocation.getArguments()(0).asInstanceOf[TaskID].getValue

        if (taskId == taskB.getId && firstKillForTaskB) {
          firstKillForTaskB = false
        }
        else {
          val update = MesosStatusUpdateEvent("", taskId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)
          system.eventStream.publish(update)
        }
        Status.DRIVER_RUNNING
      }
    })

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref.underlyingActor.periodicalRetryKills.cancel()
    ref ! RetryKills

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", s"task_$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(queue).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, times(2)).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Wait until the tasks are killed") {
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val id = "myApp".toPath
    val app = AppDefinition(id = id, instances = 5, upgradeStrategy = UpgradeStrategy(0.0),
      versionInfo = versionInfo)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[LaunchQueue]
    val tracker = mock[TaskTracker]
    val healthCheckManager = mock[HealthCheckManager]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        healthCheckManager,
        system.eventStream,
        app,
        promise))

    watch(ref)

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", s"task_$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)

    intercept[Exception] {
      Await.result(promise.future, 5.seconds)
    }

    verify(queue).resetDelay(app)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
  }
}