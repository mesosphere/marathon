package mesosphere.marathon.upgrade

import akka.actor.Props
import akka.testkit.TestActorRef
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ TaskCreationHandler, TaskTracker }
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{ MarathonTestHelper, SchedulerActions, TaskUpgradeCanceledException }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito.{ spy, verify, when }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskStartActorTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with BeforeAndAfter {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var launchQueue: LaunchQueue = _
  var taskTracker: TaskTracker = _
  var taskCreationHandler: TaskCreationHandler = _
  var metrics: Metrics = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    launchQueue = mock[LaunchQueue]
    metrics = new Metrics(new MetricRegistry)
    val leadershipModule = AlwaysElectedLeadershipModule.forActorSystem(system)
    val taskTrackerModule = MarathonTestHelper.createTaskTrackerModule(
      leadershipModule, store = new InMemoryStore, metrics = metrics)

    taskCreationHandler = taskTrackerModule.taskCreationHandler
    taskTracker = spy(taskTrackerModule.taskTracker)
  }

  for (
    (counts, description) <- Seq(
      None -> "with no item in queue",
      Some(LaunchQueueTestHelper.zeroCounts) -> "with zero count queue item"
    )
  ) {
    test(s"Start success $description") {
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      when(launchQueue.get(app.id)).thenReturn(counts)
      val ref = TestActorRef(Props(
        classOf[TaskStartActor],
        driver,
        scheduler,
        launchQueue,
        taskTracker,
        system.eventStream,
        app,
        app.instances,
        promise))

      watch(ref)

      verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances)

      for (i <- 0 until app.instances)
        system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", Nil, Nil, app.version.toString))

      Await.result(promise.future, 3.seconds) should be(())

      expectTerminated(ref)
    }
  }

  for (
    (counts, description) <- Seq(
      Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLeftToLaunch = 1)) -> "with one task left to launch",
      Some(LaunchQueueTestHelper.zeroCounts.copy(taskLaunchesInFlight = 1)) -> "with one task in flight",
      Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLaunched = 1)) -> "with one task already running"
    )
  ) {
    test(s"Start success $description") {
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      when(launchQueue.get(app.id)).thenReturn(counts)

      val ref = TestActorRef(Props(
        classOf[TaskStartActor],
        driver,
        scheduler,
        launchQueue,
        taskTracker,
        system.eventStream,
        app,
        app.instances,
        promise
      )
      )

      watch(ref)

      verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

      for (i <- 0 until (app.instances - 1))
        system
          .eventStream
          .publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", Nil, Nil, app.version.toString))

      Await.result(promise.future, 3.seconds) should be(())

      expectTerminated(ref)
    }
  }

  test("Start success with existing task in task queue") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    when(launchQueue.get(app.id)).thenReturn(None)
    val task =
      MarathonTestHelper.startingTaskForApp(app.id, appVersion = Timestamp(1024))
    taskCreationHandler.created(task).futureValue

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

    for (i <- 0 until (app.instances - 1))
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", Nil, Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val promise = Promise[Boolean]()
    val app = AppDefinition("/myApp".toPath, instances = 0)
    when(launchQueue.get(app.id)).thenReturn(None)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks") {
    val promise = Promise[Boolean]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck())
    )
    when(launchQueue.get(app.id)).thenReturn(None)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances)

    for (i <- 0 until app.instances)
      system.eventStream.publish(HealthStatusChanged(app.id, Task.Id(s"task_$i"), app.version, alive = true))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val promise = Promise[Boolean]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 0,
      healthChecks = Set(HealthCheck())
    )
    when(launchQueue.get(app.id)).thenReturn(None)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val promise = Promise[Boolean]()
    val app = AppDefinition("/myApp".toPath, instances = 5)
    when(launchQueue.get(app.id)).thenReturn(None)

    val ref = system.actorOf(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Task fails to start") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 1)

    when(launchQueue.get(app.id)).thenReturn(None)
    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances)

    system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id.forApp(app.id), "TASK_FAILED", "", app.id, "", Nil, Nil, app.version.toString))

    verify(launchQueue, Mockito.timeout(3000)).add(app, 1)

    for (i <- 0 until app.instances)
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id.forApp(app.id), "TASK_RUNNING", "", app.id, "", Nil, Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with dying existing task, reschedules, but finishes early") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)
    when(launchQueue.get(app.id)).thenReturn(None)

    val outdatedTask = MarathonTestHelper.stagedTaskForApp(app.id, appVersion = Timestamp(1024))
    val taskId = outdatedTask.taskId
    taskCreationHandler.created(outdatedTask).futureValue

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      launchQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    // wait for initial sync
    verify(launchQueue, Mockito.timeout(3000)).get(app.id)
    verify(launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

    Mockito.verifyNoMoreInteractions(launchQueue)
    Mockito.reset(launchQueue)

    // let existing task die
    when(taskTracker.countLaunchedAppTasksSync(app.id)).thenReturn(0)
    when(launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLeftToLaunch = 4)))
    system.eventStream.publish(MesosStatusUpdateEvent(
      slaveId = "", taskId = taskId, taskStatus = "TASK_ERROR", message = "", appId = app.id, host = "",
      ipAddresses = Nil, ports = Nil,
      // The version does not match the app.version so that it is filtered in StartingBehavior.
      // does that make sense?
      version = outdatedTask.launched.get.appVersion.toString
    ))

    // sync will reschedule task
    ref ! StartingBehavior.Sync
    verify(launchQueue, Mockito.timeout(3000)).get(app.id)
    verify(launchQueue, Mockito.timeout(3000)).add(app, 1)

    Mockito.verifyNoMoreInteractions(launchQueue)
    Mockito.reset(launchQueue)

    // launch 4 of the tasks
    when(launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLeftToLaunch = app.instances)))
    when(taskTracker.countLaunchedAppTasksSync(app.id)).thenReturn(4)
    List(0, 1, 2, 3) foreach { i =>
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", Nil, Nil, app.version.toString))
    }

    // it finished early
    Await.result(promise.future, 3.seconds) should be(())

    Mockito.verifyNoMoreInteractions(launchQueue)

    expectTerminated(ref)
  }
}
