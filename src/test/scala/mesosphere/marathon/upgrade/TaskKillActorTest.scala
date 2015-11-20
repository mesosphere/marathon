package mesosphere.marathon.upgrade

import akka.testkit.{ TestKit, TestActorRef }
import akka.actor.{ Props, ActorSystem }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.StoppingBehavior.SynchronizeTasks
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, Matchers, FunSuiteLike }
import org.apache.mesos.SchedulerDriver
import org.scalatest.mock.MockitoSugar
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.mutable
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.mockito.Mockito._
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.state.{ AppDefinition, PathId }

class TaskKillActorTest
    extends TestKit(ActorSystem("System"))
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

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Kill tasks") {
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()

    val tasks = Set(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(classOf[TaskKillActor], driver, PathId("/test"), taskTracker, system.eventStream, tasks, promise))

    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.getId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Kill tasks with empty task list") {
    val tasks = Set[MarathonTask]()
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(classOf[TaskKillActor], driver, PathId("/test"), taskTracker, system.eventStream, tasks, promise))

    watch(ref)

    Await.result(promise.future, 5.seconds) should be(())

    verifyZeroInteractions(driver)
    expectTerminated(ref)
  }

  test("Cancelled") {
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()

    val tasks = Set(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = system.actorOf(Props(classOf[TaskKillActor], driver, PathId("/test"), taskTracker, system.eventStream, tasks, promise))

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
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val tasks = mutable.Set(taskA, taskB)

    when(taskTracker.get(app.id))
      .thenReturn(Set.empty[MarathonTask])

    val ref = TestActorRef[TaskKillActor](Props(classOf[TaskKillActor], driver, app.id, taskTracker, system.eventStream, tasks.toSet, promise))
    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! SynchronizeTasks

    Await.result(promise.future, 5.seconds) should be(())

    expectTerminated(ref)
  }

  test("Send kill again after synchronization with task tracker") {
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val appId = PathId("/test")

    val tasks = Set(taskA, taskB)
    val promise = Promise[Unit]()

    val ref = TestActorRef[TaskKillActor](Props(classOf[TaskKillActor], driver, appId, taskTracker, system.eventStream, tasks, promise))

    when(taskTracker.get(appId)).thenReturn(Set(taskA, taskB))

    watch(ref)

    ref.underlyingActor.periodicalCheck.cancel()

    ref ! SynchronizeTasks

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.getId, "TASK_KILLED", "", PathId.empty, "", Nil, Nil, ""))

    Await.result(promise.future, 5.seconds) should be(())
    verify(driver, times(2)).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver, times(2)).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }
}
