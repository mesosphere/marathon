package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.{ ActorRef, Props }
import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.testkit._
import akka.util.Timeout
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.history.impl.HistoryActor
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.{ Task, TaskKillServiceMock }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, FrameworkIdRepository, GroupRepository, TaskFailureRepository }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.upgrade._
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, GivenWhenThen, Matchers }

import scala.collection.immutable.Set
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }

class MarathonSchedulerActorTest extends MarathonActorSupport
    with FunSuiteLike
    with Mockito
    with GivenWhenThen
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender
    with MarathonSpec {

  test("RecoversDeploymentsAndReconcilesHealthChecksOnStart") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    groupRepo.root() returns Future.successful(Group(PathId.empty, apps = Map(app.id -> app)))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      awaitAssert(verify(hcManager).reconcileWith(app.id), 5.seconds, 10.millis)
      verify(deploymentRepo, times(1)).all()
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("ReconcileTasks") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    val task = MarathonTestHelper.runningTask("task_a")

    repo.ids() returns Source.single(app.id)
    taskTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("nope".toPath, Iterable(task))))
    repo.get(app.id) returns Future.successful(Some(app))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      awaitAssert({
        killService.killed should contain (task.taskId.instanceId)
      }, 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("ScaleApps") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val tasks = Iterable(MarathonTestHelper.runningTaskForApp(app.id))

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    repo.ids() returns Source.single(app.id)
    taskTracker.specInstancesSync(app.id) returns Iterable.empty[Task]
    taskTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("nope".toPath, tasks))
    taskTracker.specInstancesSync("nope".toPath) returns tasks
    repo.get(app.id) returns Future.successful(Some(app))
    taskTracker.countLaunchedSpecInstancesSync(app.id) returns 0

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleApps

      awaitAssert(verify(queue).add(app, 1), 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("ScaleApp") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "test-app".toPath, instances = 1)

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    repo.ids() returns Source.single(app.id)
    taskTracker.specInstancesSync(app.id) returns Iterable.empty[Task]

    repo.get(app.id) returns Future.successful(Some(app))
    taskTracker.countLaunchedSpecInstancesSync(app.id) returns 0

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleApp("test-app".toPath)

      awaitAssert(verify(queue).add(app, 1), 5.seconds, 10.millis)

      expectMsg(5.seconds, AppScaled(app.id))
    } finally {
      stopActor(schedulerActor)
    }
  }

  //TODO(PODS): enable this test
  ignore("Kill tasks with scaling") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val taskA = MarathonTestHelper.stagedTaskForApp(app.id)
    val statusUpdateEvent = MesosStatusUpdateEvent(
      slaveId = "",
      taskId = taskA.taskId,
      taskStatus = "TASK_FAILED",
      message = "",
      appId = app.id,
      host = "",
      ipAddresses = None,
      ports = Nil,
      version = app.version.toString
    )

    //TODO(PODS): make this an InstanceChanged event
    //f.killService.customStatusUpdates.put(taskA.taskId, statusUpdateEvent)

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    repo.ids() returns Source.single(app.id)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable(taskA)

    repo.get(app.id) returns (Future.successful(Some(app)), Future.successful(Some(app.copy(instances = 0))))
    taskTracker.countLaunchedSpecInstancesSync(app.id) returns 0
    repo.store(any) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.taskId)))

      val Some(taskFailureEvent) = TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEvent)

      awaitAssert(verify(taskFailureEventRepository, times(1)).store(taskFailureEvent), 5.seconds, 10.millis)

      // KillTasks does no longer scale
      verify(repo, times(0)).store(any[AppDefinition])
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Kill tasks") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val taskA = MarathonTestHelper.mininimalTask(app.id)

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    repo.ids() returns Source.single(app.id)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable[Task](taskA)

    repo.get(app.id) returns (
      Future.successful(Some(app)),
      Future.successful(Some(app.copy(instances = 0))))
    taskTracker.countLaunchedSpecInstancesSync(app.id) returns 0
    repo.store(any) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.taskId)))

      awaitAssert(verify(queue).add(app, 1), 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Deployment") {
    val f = new Fixture
    import f._
    val app = AppDefinition(
      id = PathId("app1"),
      cmd = Some("cmd"),
      instances = 2,
      upgradeStrategy = UpgradeStrategy(0.5),
      versionInfo = VersionInfo.forNewConfig(Timestamp(0))
    )
    val probe = TestProbe()
    val origGroup = Group(PathId("/foo/bar"), Map(app.id -> app))

    val appNew = app.copy(
      cmd = Some("cmd new"),
      versionInfo = VersionInfo.forNewConfig(Timestamp(1000))
    )

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(appNew.id -> appNew))))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, Nil, Timestamp.now())

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      val answer = probe.expectMsgType[DeploymentSuccess]
      answer.id should be(plan.id)

      system.eventStream.unsubscribe(probe.ref)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Deployment resets rate limiter for affected apps") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/app1"),
      cmd = Some("cmd"),
      instances = 2,
      upgradeStrategy = UpgradeStrategy(0.5),
      versionInfo = VersionInfo.forNewConfig(Timestamp(0))
    )
    val probe = TestProbe()
    val taskA = MarathonTestHelper.runningTaskForApp(app.id)
    val origGroup = Group(PathId("/foo/bar"), Map(app.id -> app))
    val targetGroup = Group(PathId("/foo/bar"), Map())

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    f.taskTracker.specInstancesLaunchedSync(app.id) returns Iterable(taskA)
    f.taskTracker.specInstances(org.mockito.Matchers.eq(app.id))(any[ExecutionContext]) returns Future.successful(Iterable(taskA))
    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    val schedulerActor = f.createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      verify(f.queue, timeout(1000)).purge(app.id)
      verify(f.queue, timeout(1000)).resetDelay(app.copy(instances = 0))

      system.eventStream.unsubscribe(probe.ref)
    } finally {
      f.stopActor(schedulerActor)
    }
  }

  test("Deployment fail to acquire lock") {
    val f = new Fixture
    import f._
    val app = AppDefinition(
      id = PathId("app1"),
      cmd = Some("cmd"),
      instances = 2,
      upgradeStrategy = UpgradeStrategy(0.5),
      versionInfo = VersionInfo.forNewConfig(Timestamp(0))
    )
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    repo.store(any) returns Future.successful(Done)
    repo.get(app.id) returns Future.successful(None)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable.empty[Task]
    taskTracker.specInstancesSync(app.id) returns Iterable.empty[Task]
    repo.delete(app.id) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted]

      schedulerActor ! Deploy(plan)

      val answer = expectMsgType[CommandFailed]

      answer.cmd should equal(Deploy(plan))
      answer.reason.isInstanceOf[AppLockedException] should be(true)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Restart deployments after failover") {
    val f = new Fixture
    import f._
    val app = AppDefinition(
      id = PathId("app1"),
      cmd = Some("cmd"),
      instances = 2,
      upgradeStrategy = UpgradeStrategy(0.5),
      versionInfo = VersionInfo.forNewConfig(Timestamp(0))
    )
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    deploymentRepo.delete(any) returns Future.successful(Done)
    deploymentRepo.all() returns Source.single(plan)
    deploymentRepo.store(plan) returns Future.successful(Done)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable.empty[Task]

    val schedulerActor = system.actorOf(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        repo,
        deploymentRepo,
        hcManager,
        taskTracker,
        killService,
        queue,
        holder,
        electionService,
        system.eventStream,
        conf
      ))

    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      // This indicates that the deployment is already running,
      // which means it has successfully been restarted
      val answer = expectMsgType[CommandFailed]
      answer.cmd should equal(Deploy(plan))
      answer.reason.isInstanceOf[AppLockedException] should be(true)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Forced deployment") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5))
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    repo.store(any) returns Future.successful(Done)
    repo.get(app.id) returns Future.successful(None)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable.empty[Task]
    repo.delete(app.id) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted](10.seconds)

      schedulerActor ! Deploy(plan, force = true)

      expectMsgType[DeploymentStarted]

    } finally {
      stopActor(schedulerActor)
    }
  }

  // TODO: Fix  this test...
  ignore("Cancellation timeout - this test is really racy and fails intermittently.") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5))
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    repo.store(any) returns Future.successful(Done)
    repo.get(app.id) returns Future.successful(None)
    taskTracker.specInstancesLaunchedSync(app.id) returns Iterable.empty[Task]
    repo.delete(app.id) returns Future.successful(Done)

    val schedulerActor = TestActorRef(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        repo,
        deploymentRepo,
        hcManager,
        taskTracker,
        killService,
        queue,
        holder,
        electionService,
        system.eventStream,
        conf,
        cancellationTimeout = 0.seconds
      )
    )
    try {
      val probe = TestProbe()
      schedulerActor.tell(LocalLeadershipEvent.ElectedAsLeader, probe.testActor)
      schedulerActor.tell(Deploy(plan), probe.testActor)

      probe.expectMsgType[DeploymentStarted]

      schedulerActor.tell(Deploy(plan, force = true), probe.testActor)

      val answer = probe.expectMsgType[CommandFailed]

      answer.reason.isInstanceOf[TimeoutException] should be(true)
      answer.reason.getMessage should be

      // this test has more messages sometimes!
      // needs: probe.expectNoMsg()
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Do not run reconciliation concurrently") {
    val f = new Fixture
    import f._
    val actions = mock[SchedulerActions]
    val actionsFactory: ActorRef => SchedulerActions = _ => actions
    val schedulerActor = createActor(Some(actionsFactory))

    val reconciliationPromise = Promise[Status]()
    actions.reconcileTasks(any) returns reconciliationPromise.future
    repo.ids() returns Source.empty

    schedulerActor ! LocalLeadershipEvent.ElectedAsLeader

    schedulerActor ! MarathonSchedulerActor.ReconcileTasks
    schedulerActor ! MarathonSchedulerActor.ReconcileTasks

    reconciliationPromise.success(Status.DRIVER_RUNNING)

    expectMsg(MarathonSchedulerActor.TasksReconciled)
    expectMsg(MarathonSchedulerActor.TasksReconciled)

    schedulerActor ! MarathonSchedulerActor.ReconcileTasks
    expectMsg(MarathonSchedulerActor.TasksReconciled)

    verify(actions, times(2)).reconcileTasks(any[SchedulerDriver])
  }

  test("Concurrent reconciliation check is not preventing sequential calls") {
    val f = new Fixture
    import f._
    val actions = mock[SchedulerActions]
    val actionsFactory: ActorRef => SchedulerActions = _ => actions
    val schedulerActor = createActor(Some(actionsFactory))

    actions.reconcileTasks(any) returns Future.successful(Status.DRIVER_RUNNING)
    repo.ids() returns Source.empty

    schedulerActor ! LocalLeadershipEvent.ElectedAsLeader

    schedulerActor ! MarathonSchedulerActor.ReconcileTasks
    expectMsg(MarathonSchedulerActor.TasksReconciled)

    schedulerActor ! MarathonSchedulerActor.ReconcileTasks
    expectMsg(MarathonSchedulerActor.TasksReconciled)

    schedulerActor ! MarathonSchedulerActor.ReconcileTasks
    expectMsg(MarathonSchedulerActor.TasksReconciled)

    verify(actions, times(3)).reconcileTasks(any[SchedulerDriver])
  }

  class Fixture {
    val repo: AppRepository = mock[AppRepository]
    val groupRepo: GroupRepository = mock[GroupRepository]
    val deploymentRepo: DeploymentRepository = mock[DeploymentRepository]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val killService = new TaskKillServiceMock(system)
    val queue: LaunchQueue = mock[LaunchQueue]
    val frameworkIdRepo: FrameworkIdRepository = mock[FrameworkIdRepository]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    holder.driver = Some(driver)
    val storage: StorageProvider = mock[StorageProvider]
    val taskFailureEventRepository: TaskFailureRepository = mock[TaskFailureRepository]
    val electionService: ElectionService = mock[ElectionService]
    val schedulerActions: ActorRef => SchedulerActions = ref => {
      new SchedulerActions(
        repo, groupRepo, hcManager, taskTracker, queue, new EventStream(system), ref, killService, mock[MarathonConf])(system.dispatcher, mat)
    }
    val conf: UpgradeConfig = mock[UpgradeConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val deploymentManagerProps: SchedulerActions => Props = schedulerActions => Props(new DeploymentManager(
      repo,
      taskTracker,
      killService,
      queue,
      schedulerActions,
      storage,
      hcManager,
      system.eventStream,
      readinessCheckExecutor,
      conf
    ))

    val historyActorProps: Props = Props(new HistoryActor(system.eventStream, taskFailureEventRepository))

    def createActor(overrideActions: Option[(ActorRef) => SchedulerActions] = None) = {
      val actions = overrideActions.getOrElse(schedulerActions)
      system.actorOf(
        MarathonSchedulerActor.props(
          actions,
          deploymentManagerProps,
          historyActorProps,
          repo,
          deploymentRepo,
          hcManager,
          taskTracker,
          killService,
          queue,
          holder,
          electionService,
          system.eventStream,
          conf
        )
      )
    }
    def stopActor(ref: ActorRef): Unit = {
      watch(ref)
      system.stop(ref)
      expectTerminated(ref)
    }

    deploymentRepo.store(any) returns Future.successful(Done)
    deploymentRepo.delete(any) returns Future.successful(Done)
    deploymentRepo.all() returns Source.empty
    repo.all() returns Source.empty
    groupRepo.root() returns Future.successful(Group.empty)
    queue.get(any[PathId]) returns None
    taskTracker.countLaunchedSpecInstancesSync(any[PathId]) returns 0
    conf.killBatchCycle returns 1.seconds
    conf.killBatchSize returns 100
  }

  implicit val defaultTimeout: Timeout = 30.seconds
}
