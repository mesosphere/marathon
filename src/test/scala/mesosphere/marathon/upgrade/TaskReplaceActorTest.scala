package mesosphere.marathon.upgrade

import akka.testkit.TestKit
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

    val tasks = Set(taskA, taskB)
    val promise = Promise[Boolean]()

    val ref = system.actorOf(Props(classOf[TaskReplaceActor], driver, system.eventStream, "myApp", "version1", 5, tasks, promise))

    watch(ref)

    for (i <- 0 until 5)
      system.eventStream.publish(HealthStatusChanged("myApp", s"task_$i", true))

    Await.result(promise.future, 1.second) should be(true)
    verify(driver).killTask(TaskID.newBuilder().setValue(taskA.getId).build())
    verify(driver).killTask(TaskID.newBuilder().setValue(taskB.getId).build())

    expectTerminated(ref)
  }
}
