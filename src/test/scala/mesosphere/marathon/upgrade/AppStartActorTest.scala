package mesosphere.marathon.upgrade

import akka.testkit.{ TestProbe, TestActorRef }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import mesosphere.marathon.{ MarathonTestHelper, AppStartCanceledException, MarathonSpec, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await, Promise }

class AppStartActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  test("Without Health Checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
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

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("With Health Checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    system.eventStream.publish(HealthStatusChanged(app.id, Task.Id("task_a"), app.version, alive = true))
    system.eventStream.publish(HealthStatusChanged(app.id, Task.Id("task_b"), app.version, alive = true))

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("Failed") {
    val f = new Fixture
    f.scheduler.stopApp(any, any).asInstanceOf[Future[Unit]] returns Future.successful(())

    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    ref.stop()

    intercept[AppStartCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 2))
    verify(f.scheduler).stopApp(f.driver, app)
    expectTerminated(ref)
  }

  test("No tasks to start without health checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 0))
    expectTerminated(ref)
  }

  test("No tasks to start with health checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 10, healthChecks = Set(HealthCheck()))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startApp(f.driver, app.copy(instances = 0))
    expectTerminated(ref)
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val taskTracker: TaskTracker = MarathonTestHelper.createTaskTracker(AlwaysElectedLeadershipModule.forActorSystem(system))
    val deploymentManager: TestProbe = TestProbe()
    val deploymentStatus: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[AppStartActor] =
      TestActorRef(AppStartActor.props(deploymentManager.ref, deploymentStatus, driver, scheduler,
        launchQueue, taskTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, promise)
      )
  }
}
