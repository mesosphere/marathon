package mesosphere.marathon.upgrade

import akka.testkit.{ TestKit, TestActorRef }
import akka.actor.{ Props, ActorSystem }
import mesosphere.marathon.tasks.TaskTracker
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

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", PathId.empty, "", Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.getId, "TASK_KILLED", PathId.empty, "", Nil, ""))

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
      .thenReturn(mutable.Set.empty[MarathonTask])

    val ref = system.actorOf(Props(classOf[TaskKillActor], driver, app.id, taskTracker, system.eventStream, tasks.toSet, promise))
    watch(ref)

    Await.result(promise.future, 10.seconds) should be(())

    expectTerminated(ref)
  }
}
