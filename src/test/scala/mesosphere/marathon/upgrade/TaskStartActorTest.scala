package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ AppRepository, Timestamp, AppDefinition }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.{ MarathonTestHelper, MarathonConf, SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.apache.mesos.state.InMemoryState
import org.mockito.Mockito.{ times, spy, verify, when }
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class TaskStartActorTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with MockitoSugar
    with BeforeAndAfter
    with BeforeAndAfterAll
    with MarathonTestHelper {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var taskQueue: TaskQueue = _
  var taskTracker: TaskTracker = _
  var registry: MetricRegistry = _
  var repo: AppRepository = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskQueue = spy(new TaskQueue)
    registry = new MetricRegistry
    taskTracker = spy(new TaskTracker(new InMemoryState, defaultConfig(), registry))
    repo = mock[AppRepository]
  }

  def makeTaskStatus(id: String, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder
      .setValue(id)
      )
      .setState(state)
      .build
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Start success") {
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with tasks in taskQueue") {
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Unit]()

    taskQueue.add(app)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with existing task") {
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Unit]()

    val task = MarathonTask.newBuilder
      .setId(TaskIdUtil.newTaskId(app.id).getValue)
      .setVersion(Timestamp(1024).toString)
      .build

    taskTracker.created(app.id, task)
    taskTracker.running(app.id, makeTaskStatus(task.getId, TaskState.TASK_RUNNING))

    import system.dispatcher
    val taskApp = app.copy(version=Timestamp(1024), instances=1)
    when(repo.app(app.id, Timestamp(1024))).thenReturn(Future(Some(taskApp)))

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 4, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    val newTaskVersion = taskTracker.get(app.id).find(_.getId == task.getId).get.getVersion
    assert(newTaskVersion == app.version.toString, "Old task's version was updated")

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val app = AppDefinition("/myApp".toPath, instances = 0)
    val promise = Promise[Boolean]()

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
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
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Boolean]()

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      true,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val app = AppDefinition("/myApp".toPath, instances = 0)
    val promise = Promise[Boolean]()

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
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
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Boolean]()

    val ref = system.actorOf(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
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
    val app = AppDefinition("/myApp".toPath, instances = 1)
    val promise = Promise[Unit]()

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    taskQueue.purge(app.id)

    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_FAILED", "", app.id, "", Nil, app.version.toString))

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    verify(taskQueue, times(2)).add(app, 1)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with dying existing task, reschedules, but finishes early") {
    val app = AppDefinition("/myApp".toPath, instances = 5)
    val promise = Promise[Unit]()

    val taskId = TaskIdUtil.newTaskId(app.id)
    val task = MarathonTask.newBuilder
      .setId(taskId.getValue)
      .setVersion(Timestamp(1024).toString)
      .build

    taskTracker.created(app.id, task)
    taskTracker.running(app.id, makeTaskStatus(task.getId, TaskState.TASK_RUNNING))

    import system.dispatcher
    val taskApp = app.copy(version=Timestamp(1024), instances=1)
    when(repo.app(app.id, Timestamp(1024))).thenReturn(Future(Some(taskApp)))

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      repo,
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    // wait for initial sync
    awaitCond(taskQueue.count(app.id) == 4, 3.seconds)

    // let existing task die
    // doesn't work because it needs Zookeeper: taskTracker.terminated(app.id, taskStatus)
    // we mock instead
    when(taskTracker.count(app.id)).thenReturn(0)
    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_ERROR", "", app.id, "", Nil, task.getVersion))

    // sync will reschedule task
    ref ! StartingBehavior.Sync
    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    // launch 4 of the tasks
    when(taskTracker.count(app.id)).thenReturn(4)
    List(0, 1, 2, 3) foreach { i =>
      taskQueue.poll
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))
    }
    assert(taskQueue.count(app.id) == 1)

    // it finished early
    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }
}
