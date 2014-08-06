package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonSpec, SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class AppStopActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var taskTracker: TaskTracker = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskTracker = mock[TaskTracker]
  }

  test("Stop App") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = mutable.Set(marathonTask("task_a"), marathonTask("task_b"))

    when(taskTracker.get(app.id)).thenReturn(tasks)

    val ref = TestActorRef[AppStopActor](
      Props(
        new AppStopActor(
          driver,
          scheduler,
          taskTracker,
          system.eventStream,
          app,
          promise
        ))
    )
    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", "task_a", "TASK_KILLED", app.id, "", Nil, app.version.toString))
    system.eventStream.publish(MesosStatusUpdateEvent("", "task_b", "TASK_KILLED", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 5.seconds)

    verify(scheduler).stopApp(driver, app)
    expectTerminated(ref)
  }

  test("Stop App without running tasks") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()

    when(taskTracker.get(app.id)).thenReturn(mutable.Set.empty[MarathonTask])

    val ref = TestActorRef[AppStopActor](
      Props(
        new AppStopActor(
          driver,
          scheduler,
          taskTracker,
          system.eventStream,
          app,
          promise
        ))
    )
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(scheduler).stopApp(driver, app)
    expectTerminated(ref)
  }

  test("Failed") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = mutable.Set(marathonTask("task_a"), marathonTask("task_b"))

    when(taskTracker.get(app.id)).thenReturn(tasks)

    val ref = TestActorRef[AppStopActor](
      Props(
        new AppStopActor(
          driver,
          scheduler,
          taskTracker,
          system.eventStream,
          app,
          promise
        ))
    )
    watch(ref)

    ref.stop()

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(scheduler).stopApp(driver, app)
    expectTerminated(ref)
  }

  test("Task synchronization") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = mutable.Set(marathonTask("task_a"), marathonTask("task_b"))

    when(taskTracker.get(app.id))
      .thenReturn(tasks)
      .thenReturn(mutable.Set.empty[MarathonTask])

    val ref = system.actorOf(
      Props(
        classOf[AppStopActor],
        driver,
        scheduler,
        taskTracker,
        system.eventStream,
        app,
        promise
      )
    )
    watch(ref)

    Await.result(promise.future, 10.seconds) should be(())

    verify(scheduler).stopApp(driver, app)
    expectTerminated(ref)
  }

  def marathonTask(name: String): MarathonTask = {
    MarathonTask
      .newBuilder
      .setId(name)
      .build()
  }
}
