package mesosphere.marathon.upgrade

import akka.testkit.{ TestActorRef, TestKit }
import akka.actor.{ Props, ActorSystem }
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions, MarathonSpec }
import org.scalatest.{ BeforeAndAfterAll, Matchers }
import org.scalatest.mock.MockitoSugar
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.PathId
import scala.concurrent.{ Await, Promise }
import org.mockito.Mockito.verify
import mesosphere.marathon.event.{ MesosStatusUpdateEvent, HealthStatusChanged }
import scala.concurrent.duration._
import mesosphere.marathon.health.HealthCheck

class AppStartActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
  }

  test("Without Health Checks") {
    val app = AppDefinition(id = PathId("app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = TestActorRef[AppStartActor](
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        system.eventStream,
        app,
        2,
        promise
      )
    )
    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", "task_a", "TASK_RUNNING", app.id, "", Nil, app.version.toString))
    system.eventStream.publish(MesosStatusUpdateEvent("", "task_b", "TASK_RUNNING", app.id, "", Nil, app.version.toString))

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
        system.eventStream,
        app,
        2,
        promise
      )
    )
    watch(ref)

    system.eventStream.publish(HealthStatusChanged(app.id, "task_a", alive = true))
    system.eventStream.publish(HealthStatusChanged(app.id, "task_b", alive = true))

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
