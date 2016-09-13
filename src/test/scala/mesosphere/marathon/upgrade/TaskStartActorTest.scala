package mesosphere.marathon.upgrade

import akka.testkit.{ TestActorRef, TestProbe }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon
import mesosphere.marathon.core.instance.InstanceStatus.{ Failed, Running }
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.storage.repository.legacy.store.InMemoryStore
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.task.tracker.{ InstanceCreationHandler, InstanceTracker }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{ InstanceConversions, MarathonTestHelper, SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ spy, when }
import marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskStartActorTest
    extends MarathonActorSupport
    with FunSuiteLike
    with Matchers
    with Mockito
    with ScalaFutures
    with BeforeAndAfter
    with InstanceConversions {

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

      verify(f.launchQueue, timeout(3000)).add(app, app.instances)

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      Await.result(promise.future, 3.seconds) should be(())

      expectTerminated(ref)
    }
  }

  test("Start success with one task left to launch") {
    val f = new Fixture
    val counts = Some(LaunchQueueTestHelper.zeroCounts.copy(instancesLeftToLaunch = 1, finalInstanceCount = 1))
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    when(f.launchQueue.get(app.id)).thenReturn(counts)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    verify(f.launchQueue, timeout(3000)).add(app, app.instances - 1)

    for (i <- 0 until (app.instances - 1))
      system.eventStream.publish(f.instanceChange(app, Instance.Id(s"task-$i"), Running))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  ignore("Start success with existing task in launch queue") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    when(f.launchQueue.get(app.id)).thenReturn(None)
    val task =
      MarathonTestHelper.startingTaskForApp(app.id, appVersion = Timestamp(1024))
    f.taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(task)).futureValue

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    verify(f.launchQueue, timeout(3000)).add(app, app.instances - 1)

    for (i <- 0 until (app.instances - 1))
      system.eventStream.publish(f.instanceChange(app, Instance.Id(s"task-$i"), Running))

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

    verify(f.launchQueue, timeout(3000)).add(app, app.instances)

    for (i <- 0 until app.instances)
      system.eventStream.publish(f.healthChange(app, Instance.Id(s"task_$i"), healthy = true))

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

    verify(f.launchQueue, timeout(3000)).add(app, app.instances)

    system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Failed))

    verify(f.launchQueue, timeout(3000)).add(app, 1)

    for (i <- 0 until app.instances)
      system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  ignore("Start success with dying existing task, reschedules, but finishes early") {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)
    when(f.launchQueue.get(app.id)).thenReturn(None)

    val outdatedTask = MarathonTestHelper.stagedTaskForApp(app.id, appVersion = Timestamp(1024))
    val taskId = outdatedTask.taskId
    val instanceId = taskId.instanceId
    f.taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(outdatedTask)).futureValue

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    // wait for initial sync
    verify(f.launchQueue, timeout(3000)).get(app.id)
    verify(f.launchQueue, timeout(3000)).add(app, app.instances - 1)

    noMoreInteractions(f.launchQueue)
    reset(f.launchQueue)

    // let existing task die
    when(f.taskTracker.countLaunchedSpecInstancesSync(app.id)).thenReturn(0)
    when(f.launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(instancesLeftToLaunch = 4, finalInstanceCount = 4)))
    // The version does not match the app.version so that it is filtered in StartingBehavior.
    // does that make sense?
    system.eventStream.publish(f.instanceChange(app, instanceId, InstanceStatus.Error).copy(runSpecVersion = outdatedTask.launched.get.runSpecVersion))

    // sync will reschedule task
    ref ! StartingBehavior.Sync
    verify(f.launchQueue, timeout(3000)).get(app.id)
    verify(f.launchQueue, timeout(3000)).add(app, 1)

    noMoreInteractions(f.launchQueue)
    reset(f.launchQueue)

    // launch 4 of the tasks
    when(f.launchQueue.get(app.id)).thenReturn(Some(LaunchQueueTestHelper.zeroCounts.copy(instancesLeftToLaunch = app.instances, finalInstanceCount = 4)))
    when(f.taskTracker.countLaunchedSpecInstancesSync(app.id)).thenReturn(4)
    List(0, 1, 2, 3) foreach { i =>
      system.eventStream.publish(f.instanceChange(app, Instance.Id(s"task-$i"), Running))
    }

    // it finished early
    Await.result(promise.future, 3.seconds) should be(())

    noMoreInteractions(f.launchQueue)

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
    val taskTracker: InstanceTracker = spy(taskTrackerModule.taskTracker)
    val taskCreationHandler: InstanceCreationHandler = taskTrackerModule.taskCreationHandler
    val deploymentManager = TestProbe()
    val status: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def instanceChange(app: AppDefinition, id: Instance.Id, status: InstanceStatus): InstanceChanged = {
      val instance: Instance = mock[Instance]
      instance.instanceId returns id
      InstanceChanged(id, app.version, app.id, status, instance)
    }

    def healthChange(app: AppDefinition, id: Instance.Id, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(id, app.version, app.id, healthy)
    }

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[TaskStartActor] = TestActorRef(TaskStartActor.props(
      deploymentManager.ref, status, driver, scheduler, launchQueue, taskTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, promise
    ))
  }
}
