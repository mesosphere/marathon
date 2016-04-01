package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.testkit.TestActorRef
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ AppTerminatedEvent, HistoryActor, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure, TaskFailureRepository }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import mesosphere.marathon.upgrade.StoppingBehavior.KillNextBatch
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class AppStopActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito
    with Eventually {

  test("Stop App") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = Set(MarathonTestHelper.runningTask("task_a"), MarathonTestHelper.runningTask("task_b"))

    when(f.taskTracker.appTasksLaunchedSync(app.id)).thenReturn(tasks)

    val ref = f.stopActor(app, promise)
    watch(ref)
    val historyRef = f.historyActor()

    val statusUpdateEventA =
      MesosStatusUpdateEvent(
        slaveId = "", taskId = Task.Id("task_a"), taskStatus = "TASK_FAILED", message = "", appId = app.id, host = "",
        ipAddresses = None, ports = Nil, version = app.version.toString
      )

    val statusUpdateEventB =
      MesosStatusUpdateEvent(
        slaveId = "", taskId = Task.Id("task_b"), taskStatus = "TASK_LOST", message = "", appId = app.id, host = "",
        ipAddresses = None, ports = Nil, version = app.version.toString
      )

    val Some(taskFailureA) =
      TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEventA)

    val Some(taskFailureB) =
      TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEventB)

    system.eventStream.publish(statusUpdateEventA)
    system.eventStream.publish(statusUpdateEventB)

    Await.result(promise.future, 5.seconds)

    verify(f.driver, times(2)).killTask(any)

    system.eventStream.publish(AppTerminatedEvent(app.id))

    expectTerminated(ref)

    watch(historyRef)
    verify(f.taskFailureRepository, times(1)).store(app.id, taskFailureA)
    verify(f.taskFailureRepository, times(1)).store(app.id, taskFailureB)

    verify(f.taskFailureRepository, times(1)).expunge(app.id)
  }

  test("Stop App without running tasks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()

    when(f.taskTracker.appTasksLaunchedSync(app.id)).thenReturn(Iterable.empty[Task])

    val ref = f.stopActor(app, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.driver, times(0)).killTask(any)
    expectTerminated(ref)
  }

  test("Failed") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = Set(MarathonTestHelper.runningTask("task_a"), MarathonTestHelper.runningTask("task_b"))

    when(f.taskTracker.appTasksLaunchedSync(app.id)).thenReturn(tasks)

    val ref = f.stopActor(app, promise)
    watch(ref)

    ref.stop()

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(f.driver, times(2)).killTask(any)
    expectTerminated(ref)
  }

  test("Task synchronization") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 2)
    val promise = Promise[Unit]()
    val tasks = Set(MarathonTestHelper.runningTask("task_a"), MarathonTestHelper.runningTask("task_b"))

    when(f.taskTracker.appTasksLaunchedSync(app.id))
      .thenReturn(tasks)
      .thenReturn(Iterable.empty[Task])

    val ref = f.stopActor(app, promise)
    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! KillNextBatch

    Await.result(promise.future, 10.seconds) should be(())

    verify(f.driver, times(2)).killTask(any)
    expectTerminated(ref)
  }

  test("Kill is performed in batches") {
    //Given: one app with 20 tasks
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 20)
    val promise = Promise[Unit]()
    val tasks = (0 until 20).map { a => MarathonTestHelper.runningTask(s"task_$a") }

    //When: the App is stopped
    f.taskTracker.appTasksLaunchedSync(app.id) returns tasks
    val ref = f.stopActor(app, promise)
    watch(ref)
    ref.underlyingActor.periodicalCheck.cancel()

    //Then: the first batch is executed directly after creation
    ref.underlyingActor.idsToKill should have size 20
    eventually { ref.underlyingActor.batchKill should have size 10 }

    //And: the next batch will kill 10 tasks
    ref ! KillNextBatch
    eventually { ref.underlyingActor.batchKill should have size 0 }

    //And: all tasks are erased from the task tracker, so the future completes
    f.taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty
    ref ! KillNextBatch
    Await.result(promise.future, 10.seconds) should be(())

    //And: we make sure 20 kill commands are issued
    verify(f.driver, times(20)).killTask(any)
    expectTerminated(ref)
  }

  test("Kill in batches - retry if kill is not acknowledged") {
    //Given: one app with 20 tasks
    val f = new Fixture
    val app = AppDefinition(id = PathId("app"), instances = 20)
    val promise = Promise[Unit]()
    val tasks = (0 until 20).map { a => MarathonTestHelper.runningTask(s"task_$a") }

    //When: the App is stopped
    f.taskTracker.appTasksLaunchedSync(app.id) returns tasks
    val ref = f.stopActor(app, promise)
    watch(ref)
    ref.underlyingActor.periodicalCheck.cancel()

    //Then: the first batch is executed directly after creation
    ref.underlyingActor.idsToKill should have size 20
    eventually { ref.underlyingActor.batchKill should have size 10 }

    //And: the next batch is triggered
    ref ! KillNextBatch
    eventually { ref.underlyingActor.batchKill should have size 0 }

    //And: the next batch will trigger a synchronization
    ref ! KillNextBatch
    eventually { ref.underlyingActor.batchKill should have size 10 }

    //And: all tasks are erased from the task tracker
    ref ! KillNextBatch
    f.taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty
    ref ! KillNextBatch
    Await.result(promise.future, 10.seconds) should be(())

    //And: every task kill is issued twice: 20 tasks -> 40 kills
    verify(f.driver, times(40)).killTask(any)
    expectTerminated(ref)
  }

  class Fixture {
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val taskTracker: TaskTracker = mock[TaskTracker]
    val taskFailureRepository: TaskFailureRepository = mock[TaskFailureRepository]
    val config: UpgradeConfig = mock[UpgradeConfig]
    config.killBatchSize returns 10
    config.killBatchCycle returns 10.seconds

    def stopActor(app: AppDefinition, promise: Promise[Unit]) = TestActorRef[AppStopActor](
      AppStopActor.props(
        driver,
        taskTracker,
        system.eventStream,
        app,
        config,
        promise
      )
    )

    def historyActor() = TestActorRef[HistoryActor](
      Props(
        new HistoryActor(
          system.eventStream,
          taskFailureRepository
        )
      )
    )
  }
}
