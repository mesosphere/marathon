package mesosphere.marathon.upgrade

import akka.actor.{ Props }
import akka.testkit.{ TestActorRef }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import mesosphere.marathon.{ MarathonTestHelper, AppStartCanceledException, MarathonSpec, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verify
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await, Promise }

class AppStartActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var taskQueue: LaunchQueue = _
  var taskTracker: TaskTracker = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskQueue = mock[LaunchQueue]
    taskTracker = MarathonTestHelper.createTaskTracker(AlwaysElectedLeadershipModule.forActorSystem(system))
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
        slaveId = "", taskId = Task.Id("task_a"),
        taskStatus = "TASK_RUNNING", message = "", appId = app
          .id, host = "", ipAddresses = None, ports = Nil, version = app.version.toString
      )
    )
    system.eventStream.publish(
      MesosStatusUpdateEvent(
        slaveId = "", taskId = Task.Id("task_b"), taskStatus = "TASK_RUNNING", message = "", appId = app.id, host = "",
        ipAddresses = None, ports = Nil, version = app.version.toString
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

    system.eventStream.publish(HealthStatusChanged(app.id, Task.Id("task_a"), app.version, alive = true))
    system.eventStream.publish(HealthStatusChanged(app.id, Task.Id("task_b"), app.version, alive = true))

    Await.result(promise.future, 5.seconds)

    verify(scheduler).startApp(driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("Failed") {
    scheduler.stopApp(any, any).asInstanceOf[Future[Unit]] returns Future.successful(())

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
