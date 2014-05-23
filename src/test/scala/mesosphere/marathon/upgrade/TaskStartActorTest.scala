package mesosphere.marathon.upgrade

import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Matchers, FunSuiteLike}
import akka.actor.{Props, ActorSystem}
import mesosphere.marathon.tasks.TaskQueue
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.{TaskUpgradeCancelledException, TaskFailedException}
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.Protos.MarathonTask

class TaskStartActorTest
  extends TestKit(ActorSystem("System"))
  with FunSuiteLike
  with Matchers
  with BeforeAndAfterAll {

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Start success") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp", instances = 5)

    val ref = system.actorOf(Props(classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app) == 5, 1.second)

    for (task <- taskQueue.removeAll())
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 1.second) should be(true)

    expectTerminated(ref)
  }

  test("Start failure") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp", instances = 5)

    val ref = system.actorOf(Props(classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app) == 5, 5.seconds)

    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_FAILED", app.id, "", Nil, app.version.toString))

    val ex = intercept[TaskFailedException] {
      Await.result(promise.future, 5.seconds) should be(true)
    }

    ex.getMessage should equal("Task failed during start")

    expectTerminated(ref)
  }

  test("Cancelled") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp", instances = 5)

    val ref = system.actorOf(Props(classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCancelledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }
}
