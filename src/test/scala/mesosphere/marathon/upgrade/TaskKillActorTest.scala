package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.testkit.TestActorRef
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.upgrade.StoppingBehavior.SynchronizeTasks
import mesosphere.marathon.{ MarathonTestHelper, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
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
    with MockitoSugar {

  var taskTracker: TaskTracker = _
  var driver: SchedulerDriver = _

  before {
    taskTracker = mock[TaskTracker]
    driver = mock[SchedulerDriver]
  }

  test("Kill tasks") {
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(new TaskKillActor(driver, PathId("/test"), taskTracker, system.eventStream, tasks.map(_.taskId), promise)))

    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.taskId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.taskId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(driver).killTask(taskA.taskId.mesosTaskId)
    verify(driver).killTask(taskB.taskId.mesosTaskId)

    expectTerminated(ref)
  }

  test("Kill tasks with empty task list") {
    val tasks = Iterable.empty[Task]
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(new TaskKillActor(driver, PathId("/test"), taskTracker, system.eventStream, tasks.map(_.taskId), promise)))

    watch(ref)

    Await.result(promise.future, 5.seconds) should be(())

    verifyZeroInteractions(driver)
    expectTerminated(ref)
  }

  test("Cancelled") {
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = system.actorOf(Props(new TaskKillActor(driver, PathId("/test"), taskTracker, system.eventStream, tasks.map(_.taskId), promise)))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The operation has been cancelled")

    expectTerminated(ref)
  }

  test("Task synchronization") {
    val app = AppDefinition(id = PathId("/app"), instances = 2)
    val promise = Promise[Unit]()
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val tasks = mutable.Iterable(taskA, taskB)

    when(taskTracker.appTasksLaunchedSync(app.id)).thenReturn(mutable.Iterable.empty[Task])

    val ref = TestActorRef[TaskKillActor](Props(new TaskKillActor(driver, app.id, taskTracker, system.eventStream, tasks.map(_.taskId), promise)))
    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! SynchronizeTasks

    Await.result(promise.future, 5.seconds) should be(())

    expectTerminated(ref)
  }

  test("Send kill again after synchronization with task tracker") {
    val taskA = MarathonTestHelper.runningTask("taskA_id")
    val taskB = MarathonTestHelper.runningTask("taskB_id")
    val appId = PathId("/test")

    val tasks = Iterable(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = TestActorRef[TaskKillActor](Props(new TaskKillActor(driver, appId, taskTracker, system.eventStream, tasks.map(_.taskId), promise)))

    when(taskTracker.appTasksLaunchedSync(appId)).thenReturn(Iterable(taskA, taskB))

    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! SynchronizeTasks

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.taskId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.taskId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(driver, times(2)).killTask(taskA.launchedMesosId.get)
    verify(driver, times(2)).killTask(taskB.launchedMesosId.get)

    expectTerminated(ref)
  }
}
