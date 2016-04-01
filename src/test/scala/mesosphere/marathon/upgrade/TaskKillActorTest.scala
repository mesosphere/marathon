package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.testkit.TestActorRef
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import mesosphere.marathon.upgrade.StoppingBehavior.KillNextBatch
import mesosphere.marathon.{ MarathonTestHelper, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskKillActorTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfter
    with Mockito {

  test("Kill tasks") {
    val f = new Fixture
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    f.taskTracker.appTasksLaunchedSync(any) returns tasks
    val ref = f.killActor(PathId("/test"), tasks.map(_.taskId), promise)
    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.taskId, "TASK_KILLED", "", PathId.empty, "", None, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.taskId, "TASK_KILLED", "", PathId.empty, "", None, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(f.driver).killTask(taskA.taskId.mesosTaskId)
    verify(f.driver).killTask(taskB.taskId.mesosTaskId)

    expectTerminated(ref)
  }

  test("Kill tasks with empty task list") {
    val f = new Fixture
    val tasks = Iterable.empty[Task]
    val promise = Promise[Unit]()

    f.taskTracker.appTasksLaunchedSync(any) returns Iterable.empty
    val ref = f.killActor(PathId("/test"), tasks.map(_.taskId), promise)
    watch(ref)

    Await.result(promise.future, 5.seconds) should be(())

    verifyZeroInteractions(f.driver)
    expectTerminated(ref)
  }

  test("Cancelled") {
    val f = new Fixture
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    f.taskTracker.appTasksLaunchedSync(any) returns tasks
    val ref = f.killActor(PathId("/test"), tasks.map(_.taskId), promise)
    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The operation has been cancelled")

    expectTerminated(ref)
  }

  test("Task synchronization") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 2)
    val promise = Promise[Unit]()
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val tasks = mutable.Iterable(taskA, taskB)

    f.taskTracker.appTasksLaunchedSync(any) returns tasks
    val ref = f.killActor(app.id, tasks.map(_.taskId), promise)
    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    f.taskTracker.appTasksLaunchedSync(any) returns Iterable.empty
    ref ! KillNextBatch

    Await.result(promise.future, 5.seconds) should be(())

    expectTerminated(ref)
  }

  test("Send kill again after synchronization with task tracker") {
    val f = new Fixture
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val appId = PathId("/test")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    f.taskTracker.appTasksLaunchedSync(any) returns tasks
    val ref = f.killActor(appId, tasks.map(_.taskId), promise)
    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()
    ref ! KillNextBatch

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.taskId, "TASK_KILLED", "", PathId.empty, "", None, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.taskId, "TASK_KILLED", "", PathId.empty, "", None, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(f.driver, times(2)).killTask(taskA.launchedMesosId.get)
    verify(f.driver, times(2)).killTask(taskB.launchedMesosId.get)

    expectTerminated(ref)
  }

  class Fixture {
    val conf = mock[UpgradeConfig]
    val taskTracker: TaskTracker = mock[TaskTracker]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val config: UpgradeConfig = mock[UpgradeConfig]

    config.killBatchCycle returns 30.seconds
    config.killBatchSize returns 100

    def killActor(appId: PathId, tasks: Iterable[Task.Id], promise: Promise[Unit]) = {
      TestActorRef[TaskKillActor](TaskKillActor.props(driver, appId, taskTracker, system.eventStream, tasks, config, promise))
    }
  }
}
