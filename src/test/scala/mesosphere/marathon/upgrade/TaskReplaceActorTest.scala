package mesosphere.marathon.upgrade

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, Matchers, FunSuiteLike}
import org.scalatest.mock.MockitoSugar
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.{Await, Promise}
import org.mockito.Mockito._
import mesosphere.marathon.event.HealthStatusChanged
import org.apache.mesos.Protos.TaskID
import scala.concurrent.duration._
import mesosphere.marathon.TaskUpgradeCancelledException
import mesosphere.marathon.tasks.TaskQueue
import mesosphere.marathon.api.v1.AppDefinition

class TaskReplaceActorTest
  extends TestKit(ActorSystem("System"))
  with FunSuiteLike
  with Matchers
  with BeforeAndAfterAll
  with MockitoSugar {

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Replace success") {
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()

    val ref = TestActorRef(Props(
      classOf[TaskReplaceActor],
      driver,
      queue,
      system.eventStream,
      AppDefinition(id = "myApp", instances = 5),
      5,
      tasks,
      promise))

    watch(ref)

    for (i <- 0 until 5)
      system.eventStream.publish(HealthStatusChanged("myApp", s"task_$i", true))

    Await.result(promise.future, 5.seconds) should be(true)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Replace with limited nr of running tasks") {
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()
    val app = AppDefinition(id = "myApp", instances = 5)

    val ref = TestActorRef(Props(
      classOf[TaskReplaceActor],
      driver,
      queue,
      system.eventStream,
      app,
      3,
      tasks,
      promise))

    watch(ref)

    for (i <- 0 until 5)
      system.eventStream.publish(HealthStatusChanged("myApp", s"task_$i", true))

    Await.result(promise.future, 5.seconds) should be(true)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())
    verify(queue, times(2)).add(app)

    expectTerminated(ref)
  }

  test("Cancelled") {
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val queue = mock[TaskQueue]

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()

    val ref = system.actorOf(Props(
      classOf[TaskReplaceActor],
      driver,
      queue,
      system.eventStream,
      AppDefinition(id = "myApp", instances = 1),
      0,
      tasks,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCancelledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }
}
