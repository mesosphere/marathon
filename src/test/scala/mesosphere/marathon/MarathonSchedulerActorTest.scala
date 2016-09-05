package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor.{ ActorRef, Props }
import akka.event.EventStream
import akka.testkit._
import akka.util.Timeout
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.{ Task, TaskKillServiceMock }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.history.impl.HistoryActor
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.upgrade._
import mesosphere.util.state.FrameworkIdUtil
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, GivenWhenThen, Matchers }

import scala.collection.immutable.{ Seq, Set }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }

class MarathonSchedulerActorTest extends MarathonActorSupport
    with FunSuiteLike
    with Mockito
    with GivenWhenThen
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  test("RecoversDeploymentsAndReconcilesHealthChecksOnStart") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    groupRepo.rootGroup() returns Future.successful(Some(Group.apply(PathId.empty, apps = Map(app.id -> app))))

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

    repo.allPathIds() returns Future.successful(Seq(app.id))
    taskTracker.tasksByApp()(any[ExecutionContext]) returns Future.successful(TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks("nope".toPath, Iterable(task))))
    repo.currentVersion(app.id) returns Future.successful(Some(app))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      awaitAssert({
        killService.killed should contain (task.taskId)
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
    repo.allPathIds() returns Future.successful(Seq(app.id))
    taskTracker.appTasksSync(app.id) returns Iterable.empty[Task]
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks("nope".toPath, tasks))
    taskTracker.appTasksSync("nope".toPath) returns tasks
    repo.currentVersion(app.id) returns Future.successful(Some(app))
    taskTracker.countLaunchedAppTasksSync(app.id) returns 0

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
    repo.allIds() returns Future.successful(Seq(app.id.toString))
    taskTracker.appTasksSync(app.id) returns Iterable.empty[Task]

    repo.currentVersion(app.id) returns Future.successful(Some(app))
    taskTracker.countLaunchedAppTasksSync(app.id) returns 0

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

  test("Kill tasks with scaling") {
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
    f.killService.customStatusUpdates.put(taskA.taskId, statusUpdateEvent)

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    repo.allIds() returns Future.successful(Seq(app.id.toString))
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable(taskA)

    repo.currentVersion(app.id) returns (Future.successful(Some(app)), Future.successful(Some(app.copy(instances = 0))))
    taskTracker.countLaunchedAppTasksSync(app.id) returns 0
    repo.store(any) returns Future.successful(app)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA)))

      val Some(taskFailureEvent) = TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEvent)

      awaitAssert(verify(taskFailureEventRepository, times(1)).store(app.id, taskFailureEvent), 5.seconds, 10.millis)

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
    repo.allIds() returns Future.successful(Seq(app.id.toString))
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable[Task](taskA)

    repo.currentVersion(app.id) returns (
      Future.successful(Some(app)),
      Future.successful(Some(app.copy(instances = 0))))
    taskTracker.countLaunchedAppTasksSync(app.id) returns 0
    repo.store(any) returns Future.successful(app)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA)))

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
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(0))
    )
    val probe = TestProbe()
    val origGroup = Group(PathId("/foo/bar"), Map(app.id -> app))

    val appNew = app.copy(
      cmd = Some("cmd new"),
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(1000))
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
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(0))
    )
    val probe = TestProbe()
    val taskA = MarathonTestHelper.runningTaskForApp(app.id)
    val origGroup = Group(PathId("/foo/bar"), Map(app.id -> app))
    val targetGroup = Group(PathId("/foo/bar"), Map())

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    f.taskTracker.appTasksLaunchedSync(app.id) returns Iterable(taskA)
    f.taskTracker.appTasks(org.mockito.Matchers.eq(app.id))(any[ExecutionContext]) returns Future.successful(Iterable(taskA))
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
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(0))
    )
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    repo.store(any) returns Future.successful(app)
    repo.currentVersion(app.id) returns Future.successful(None)
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty[Task]
    taskTracker.appTasksSync(app.id) returns Iterable.empty[Task]
    repo.expunge(app.id) returns Future.successful(Nil)

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
      versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(0))
    )
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    deploymentRepo.expunge(any) returns Future.successful(Seq(true))
    deploymentRepo.all() returns Future.successful(Seq(plan))
    deploymentRepo.store(plan) returns Future.successful(plan)
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty[Task]

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

    repo.store(any) returns Future.successful(app)
    repo.currentVersion(app.id) returns Future.successful(None)
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty[Task]
    repo.expunge(app.id) returns Future.successful(Nil)

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

  test("Cancellation timeout") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5))
    val group = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(Group.empty, group)

    repo.store(any) returns Future.successful(app)
    repo.currentVersion(app.id) returns Future.successful(None)
    taskTracker.appTasksLaunchedSync(app.id) returns Iterable.empty[Task]
    repo.expunge(app.id) returns Future.successful(Nil)

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
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted]

      schedulerActor ! Deploy(plan, force = true)

      val answer = expectMsgType[CommandFailed]

      answer.reason.isInstanceOf[TimeoutException] should be(true)
      answer.reason.getMessage should be
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
    repo.allIds() returns Future.successful(Nil)

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
    repo.allIds() returns Future.successful(Nil)

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
    val taskTracker: TaskTracker = mock[TaskTracker]
    val killService = new TaskKillServiceMock(system)
    val queue: LaunchQueue = mock[LaunchQueue]
    val frameworkIdUtil: FrameworkIdUtil = mock[FrameworkIdUtil]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    holder.driver = Some(driver)
    val storage: StorageProvider = mock[StorageProvider]
    val taskFailureEventRepository: TaskFailureRepository = mock[TaskFailureRepository]
    val electionService: ElectionService = mock[ElectionService]
    val schedulerActions: ActorRef => SchedulerActions = ref => {
      new SchedulerActions(
        repo, groupRepo, hcManager, taskTracker, queue, new EventStream(system), ref, killService, mock[MarathonConf])(system.dispatcher)
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

    deploymentRepo.store(any) answers { args =>
      Future.successful(args(0).asInstanceOf[DeploymentPlan])
    }

    deploymentRepo.expunge(any) returns Future.successful(Seq(true))
    deploymentRepo.all() returns Future.successful(Nil)
    repo.apps() returns Future.successful(Nil)
    groupRepo.rootGroup() returns Future.successful(None)
    queue.get(any[PathId]) returns None
    taskTracker.countLaunchedAppTasksSync(any[PathId]) returns 0
    conf.killBatchCycle returns 1.seconds
    conf.killBatchSize returns 100
  }

  implicit val defaultTimeout: Timeout = 30.seconds
}
