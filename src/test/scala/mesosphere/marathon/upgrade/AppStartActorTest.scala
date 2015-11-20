package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ AppStartCanceledException, MarathonSpec, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verify
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class AppStartActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var taskQueue: LaunchQueue = _
  var taskTracker: TaskTracker = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskQueue = mock[LaunchQueue]
    taskTracker = createTaskTracker()
  }

  test("Without Health Checks") {
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        2,
        promise
      )
    )
    watch(ref)

    system.eventStream.publish(
      MesosStatusUpdateEvent(
        slaveId = "", taskId = "task_a",
        taskStatus = "TASK_RUNNING", message = "", appId = app
          .id, host = "", ipAddresses = Nil, ports = Nil, version = app.version.toString
      )
    )
    system.eventStream.publish(
      MesosStatusUpdateEvent(
        slaveId = "", taskId = "task_b", taskStatus = "TASK_RUNNING", message = "", appId = app.id, host = "",
        ipAddresses = Nil, ports = Nil, version = app.version.toString
      )
    )

    Await.result(promise.future, 5.seconds)

    verify(scheduler).startApp(driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("With Health Checks") {
    val app = AppDefinition(id = PathId("app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        2,
        promise
      )
    )
    watch(ref)

    system.eventStream.publish(HealthStatusChanged(app.id, "task_a", app.version.toString, alive = true))
    system.eventStream.publish(HealthStatusChanged(app.id, "task_b", app.version.toString, alive = true))

    Await.result(promise.future, 5.seconds)

    verify(scheduler).startApp(driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("Failed") {
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        2,
        promise
      )
    )
    watch(ref)

    ref.stop()

    intercept[AppStartCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(scheduler).startApp(driver, app.copy(instances = 2))
    verify(scheduler).stopApp(driver, app)
    expectTerminated(ref)
  }

  test("No tasks to start without health checks") {
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        0,
        promise
      )
    )
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(scheduler).startApp(driver, app.copy(instances = 0))
    expectTerminated(ref)
  }

  test("No tasks to start with health checks") {
    val app = AppDefinition(id = PathId("app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        0,
        promise
      )
    )
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(scheduler).startApp(driver, app.copy(instances = 0))
    expectTerminated(ref)
  }
}
