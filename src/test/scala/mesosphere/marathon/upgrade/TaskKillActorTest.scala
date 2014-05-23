package mesosphere.marathon.upgrade

import akka.testkit.TestKit
import akka.actor.{Props, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, Matchers, FunSuiteLike}
import org.apache.mesos.SchedulerDriver
import org.scalatest.mock.MockitoSugar
import mesosphere.marathon.Protos.MarathonTask
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import org.mockito.Mockito.verify
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.TaskUpgradeCancelledException

class TaskKillActorTest
  extends TestKit(ActorSystem("System"))
  with FunSuiteLike
  with Matchers
  with BeforeAndAfterAll
  with MockitoSugar {

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Kill tasks") {
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()

    val ref = system.actorOf(Props(classOf[TaskKillActor], driver, system.eventStream, tasks, promise))

    watch(ref)

    system.eventStream.publish(MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", "", "", Nil, ""))
    system.eventStream.publish(MesosStatusUpdateEvent("", taskB.getId, "TASK_KILLED", "", "", Nil, ""))

    Await.result(promise.future, 5.seconds) should be(true)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val driver = mock[SchedulerDriver]
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()

    val ref = system.actorOf(Props(classOf[TaskKillActor], driver, system.eventStream, tasks, promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCancelledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }
}
