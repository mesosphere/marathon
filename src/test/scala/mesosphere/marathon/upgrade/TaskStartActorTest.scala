package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskQueue
import org.mockito.Mockito.{ spy, times, verify }
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

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
    val promise = Promise[Unit]()
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

    awaitCond(taskQueue.count(app) == 5, 3.seconds)

    for (task <- taskQueue.removeAll())
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

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

    Await.result(promise.future, 3.seconds) should be(())

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

    awaitCond(taskQueue.count(app) == 5, 3.seconds)

    for ((_, i) <- taskQueue.removeAll().zipWithIndex)
      system.eventStream.publish(HealthStatusChanged(app.id, s"task_${i}", app.version.toString, true))

    Await.result(promise.future, 3.seconds) should be(())

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

    Await.result(promise.future, 3.seconds) should be(())

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

  test("Task fails to start") {
    val taskQueue = spy(new TaskQueue)
    val promise = Promise[Unit]()
    val app = AppDefinition("myApp".toPath, instances = 1)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      taskQueue,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app) == 1, 3.seconds)

    for (task <- taskQueue.removeAll())
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_FAILED", app.id, "", Nil, app.version.toString))

    awaitCond(taskQueue.count(app) == 1, 3.seconds)

    verify(taskQueue, times(2)).add(app)

    for (task <- taskQueue.removeAll())
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }
}
