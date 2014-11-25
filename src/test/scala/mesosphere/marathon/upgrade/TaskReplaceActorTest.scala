package mesosphere.marathon.upgrade

import akka.actor.ActorSystem
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, UpgradeStrategy }
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import org.apache.mesos.Protos.{ Status, TaskID }
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskReplaceActorTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Replace without health checks") {
    val app = AppDefinition(id = "myApp".toPath, instances = 5, upgradeStrategy = UpgradeStrategy(0.0))
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]
    val tracker = mock[TaskTracker]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        system.eventStream,
        app,
        promise))

    watch(ref)

    for (i <- 0 until app.instances)
      ref ! MesosStatusUpdateEvent("", s"task_$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString)

    Await.result(promise.future, 5.seconds)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Replace with health checks") {
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(0.0))

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]
    val tracker = mock[TaskTracker]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
        system.eventStream,
        app,
        promise))

    watch(ref)

    for (i <- 0 until app.instances)
      ref ! HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true)

    Await.result(promise.future, 5.seconds)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Replace with minimum running tasks") {
    val app = AppDefinition(
      id = "myApp".toPath,
      instances = 2,
      healthChecks = Set(HealthCheck()),
      upgradeStrategy = UpgradeStrategy(1.0)
    )

    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]
    val tracker = mock[TaskTracker]

    var oldTaskCount = 2

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))
    when(driver.killTask(any[TaskID])).thenAnswer(new Answer[Status] {
      override def answer(invocation: InvocationOnMock): Status = {
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
        system.eventStream,
        app,
        promise))

    watch(ref)

    ref ! HealthStatusChanged(app.id, s"task_0", app.version.toString, alive = true)

    oldTaskCount should be(1)

    ref ! HealthStatusChanged(app.id, s"task_1", app.version.toString, alive = true)

    oldTaskCount should be(0)

    Await.result(promise.future, 5.seconds)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val app = AppDefinition(id = "myApp".toPath, instances = 2)
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]
    val tracker = mock[TaskTracker]

    when(tracker.get(app.id)).thenReturn(Set(taskA, taskB))

    val promise = Promise[Unit]()

    val ref = TestActorRef(
      new TaskReplaceActor(
        driver,
        queue,
        tracker,
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
}