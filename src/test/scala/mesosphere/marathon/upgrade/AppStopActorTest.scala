package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestProbe, TestActorRef, TestKit }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ HistoryActor, AppTerminatedEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ TaskFailure, TaskFailureRepository, AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.StoppingBehavior.SynchronizeTasks
import mesosphere.marathon.{ MarathonSpec, SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

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
  var taskFailureRepository: TaskFailureRepository = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskTracker = mock[TaskTracker]
    taskFailureRepository = mock[TaskFailureRepository]
  }

  test("Stop App") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = Set(marathonTask("task_a"), marathonTask("task_b"))

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

    val historyRef = TestActorRef[HistoryActor](
      Props(
        new HistoryActor(
          system.eventStream,
          taskFailureRepository
        )
      )
    )

    val statusUpdateEventA =
      MesosStatusUpdateEvent("", "task_a", "TASK_FAILED", "", app.id, "", Nil, app.version.toString)

    val statusUpdateEventB =
      MesosStatusUpdateEvent("", "task_b", "TASK_LOST", "", app.id, "", Nil, app.version.toString)

    val Some(taskFailureA) =
      TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEventA)

    val Some(taskFailureB) =
      TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEventB)

    system.eventStream.publish(statusUpdateEventA)
    system.eventStream.publish(statusUpdateEventB)

    Await.result(promise.future, 5.seconds)

    verify(scheduler).stopApp(driver, app)

    system.eventStream.publish(AppTerminatedEvent(app.id))

    expectTerminated(ref)

    watch(historyRef)
    verify(taskFailureRepository, times(1)).store(app.id, taskFailureA)
    verify(taskFailureRepository, times(1)).store(app.id, taskFailureB)

    verify(taskFailureRepository, times(1)).expunge(app.id)
  }

  test("Stop App without running tasks") {
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()

    when(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])

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
    val tasks = Set(marathonTask("task_a"), marathonTask("task_b"))

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
    val tasks = Set(marathonTask("task_a"), marathonTask("task_b"))

    when(taskTracker.get(app.id))
      .thenReturn(tasks)
      .thenReturn(Set.empty[MarathonTask])

    val ref = TestActorRef[AppStopActor](
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

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! SynchronizeTasks

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
