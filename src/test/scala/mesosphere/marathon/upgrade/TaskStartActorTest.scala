package mesosphere.marathon.upgrade

import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.state.PathId._
import org.scalatest.{ BeforeAndAfterAll, Matchers, FunSuiteLike }
import akka.actor.{ Props, ActorSystem }
import mesosphere.marathon.tasks.TaskQueue
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.TaskUpgradeCanceledException

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
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app) == 5, 1.second)

    for (task <- taskQueue.removeAll())
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 1.second) should be(true)

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 0)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    Await.result(promise.future, 1.second) should be(true)

    expectTerminated(ref)
  }

  test("Start with health checks") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      true,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app) == 5, 1.second)

    for ((_, i) <- taskQueue.removeAll().zipWithIndex)
      system.eventStream.publish(HealthStatusChanged(app.id, s"task_${i}", app.version.toString, true))

    Await.result(promise.future, 1.second) should be(true)

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 0)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      true,
      promise))

    watch(ref)

    Await.result(promise.future, 1.second) should be(true)

    expectTerminated(ref)
  }

  test("Cancelled") {
    val taskQueue = new TaskQueue
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = system.actorOf(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }
}
