package mesosphere.marathon.upgrade

import akka.testkit.{ TestProbe, TestActorRef }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.{ TaskCreationHandler, TaskTracker }
import mesosphere.marathon.event.{ DeploymentStatus, HealthStatusChanged, MesosStatusUpdateEvent }
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

  for (
    (counts, description) <- Seq(
      None -> "with no item in queue",
      Some(LaunchQueueTestHelper.zeroCounts) -> "with zero count queue item"
    )
  ) {
    test(s"Start success $description") {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      when(f.launchQueue.get(app.id)).thenReturn(counts)
      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances)

      for (i <- 0 until app.instances)
        system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

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
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      when(f.launchQueue.get(app.id)).thenReturn(counts)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

      for (i <- 0 until (app.instances - 1))
        system
          .eventStream
          .publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

      Await.result(promise.future, 3.seconds) should be(())

      expectTerminated(ref)
    }
  }

  test("Start success with existing task in launch queue") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    when(f.launchQueue.get(app.id)).thenReturn(None)
    val task =
      MarathonTestHelper.startingTaskForApp(app.id, appVersion = Timestamp(1024))
    f.taskCreationHandler.created(TaskStateOp.LaunchEphemeral(task)).futureValue

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

    for (i <- 0 until (app.instances - 1))
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 0)
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck())
    )
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances)

    for (i <- 0 until app.instances)
      system.eventStream.publish(HealthStatusChanged(app.id, Task.Id(s"task_$i"), app.version, alive = true))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 0,
      healthChecks = Set(HealthCheck())
    )
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Task fails to start") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 1)

    when(f.launchQueue.get(app.id)).thenReturn(None)
    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances)

    system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id.forApp(app.id), "TASK_FAILED", "", app.id, "", None, Nil, app.version.toString))

    verify(f.launchQueue, Mockito.timeout(3000)).add(app, 1)

    for (i <- 0 until app.instances)
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id.forApp(app.id), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with dying existing task, reschedules, but finishes early") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val outdatedTask = MarathonTestHelper.stagedTaskForApp(app.id, appVersion = Timestamp(1024))
    val taskId = outdatedTask.taskId
    f.taskCreationHandler.created(TaskStateOp.LaunchEphemeral(outdatedTask)).futureValue

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    // wait for initial sync
    verify(f.launchQueue, Mockito.timeout(3000)).get(app.id)
    verify(f.launchQueue, Mockito.timeout(3000)).add(app, app.instances - 1)

    Mockito.verifyNoMoreInteractions(f.launchQueue)
    Mockito.reset(f.launchQueue)

    // let existing task die
    when(f.taskTracker.countLaunchedAppTasksSync(app.id)).thenReturn(0)
    when(f.launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLeftToLaunch = 4)))
    system.eventStream.publish(MesosStatusUpdateEvent(
      slaveId = "", taskId = taskId, taskStatus = "TASK_ERROR", message = "", appId = app.id, host = "",
      ipAddresses = None, ports = Nil,
      // The version does not match the app.version so that it is filtered in StartingBehavior.
      // does that make sense?
      version = outdatedTask.launched.get.appVersion.toString
    ))

    // sync will reschedule task
    ref ! StartingBehavior.Sync
    verify(f.launchQueue, Mockito.timeout(3000)).get(app.id)
    verify(f.launchQueue, Mockito.timeout(3000)).add(app, 1)

    Mockito.verifyNoMoreInteractions(f.launchQueue)
    Mockito.reset(f.launchQueue)

    // launch 4 of the tasks
    when(f.launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(tasksLeftToLaunch = app.instances)))
    when(f.taskTracker.countLaunchedAppTasksSync(app.id)).thenReturn(4)
    List(0, 1, 2, 3) foreach { i =>
      system.eventStream.publish(MesosStatusUpdateEvent("", Task.Id(s"task-$i"), "TASK_RUNNING", "", app.id, "", None, Nil, app.version.toString))
    }

    // it finished early
    Await.result(promise.future, 3.seconds) should be(())

    Mockito.verifyNoMoreInteractions(f.launchQueue)

    expectTerminated(ref)
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val metrics: Metrics = new Metrics(new MetricRegistry)
    val leadershipModule = AlwaysElectedLeadershipModule.forActorSystem(system)
    val taskTrackerModule = MarathonTestHelper.createTaskTrackerModule(
      leadershipModule, store = new InMemoryStore, metrics = metrics)
    val taskTracker: TaskTracker = spy(taskTrackerModule.taskTracker)
    val taskCreationHandler: TaskCreationHandler = taskTrackerModule.taskCreationHandler
    val deploymentManager = TestProbe()
    val status: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[TaskStartActor] = TestActorRef(TaskStartActor.props(
      deploymentManager.ref, status, driver, scheduler, launchQueue, taskTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, promise
    ))
  }
}
