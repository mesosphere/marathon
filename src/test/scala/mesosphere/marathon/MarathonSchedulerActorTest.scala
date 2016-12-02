package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.{ ActorRef, Props }
import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.testkit._
import akka.util.Timeout
import mesosphere.Unstable
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.history.impl.HistoryActor
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, FrameworkIdRepository, GroupRepository, TaskFailureRepository, _ }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.{ GroupCreation, MarathonActorSupport, MarathonSpec, Mockito }
import mesosphere.marathon.upgrade._
import org.apache.mesos.Protos.{ Status, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, GivenWhenThen, Matchers }

import scala.collection.immutable.Set
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }

class MarathonSchedulerActorTest extends MarathonActorSupport
    with FunSuiteLike
    with Mockito
    with GivenWhenThen
    with Matchers
    with BeforeAndAfter
    with ImplicitSender
    with MarathonSpec
    with GroupCreation {

  test("RecoversDeploymentsAndReconcilesHealthChecksOnStart") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    groupRepo.root() returns Future.successful(createRootGroup(apps = Map(app.id -> app)))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      awaitAssert(verify(hcManager).reconcileWith(app.id), 5.seconds, 10.millis)
      verify(deploymentRepo, times(1)).all()
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Reconcile orphan instance of unknown app - instance should be killed") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/deleted-app".toPath, instances = 1)
    val orphanedInstance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

    appRepo.ids() returns Source.empty[PathId]
    instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq(orphanedInstance))))
    appRepo.get(app.id) returns Future.successful(None)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      awaitAssert({
        killService.killed should contain (orphanedInstance.instanceId)
      }, 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Terminal tasks should not be submitted in reconciliation") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instance = TestInstanceBuilder.newBuilder(app.id).addTaskUnreachable(containerName = Some("unreachable")).addTaskRunning().addTaskGone(containerName = Some("gone")).getInstance()

    appRepo.ids() returns Source.single(app.id)
    instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq(instance))))
    appRepo.get(app.id) returns Future.successful(Some(app))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      val expectedStatus: java.util.Collection[TaskStatus] = TaskStatusCollector.collectTaskStatusFor(Seq(instance))
      assert(expectedStatus.size() == 2, "Only non-terminal tasks should be expected to be reconciled")
      awaitAssert({
        driver.reconcileTasks(expectedStatus)
      }, 5.seconds, 10.millis)
      awaitAssert({
        driver.reconcileTasks(java.util.Arrays.asList())
      }, 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Terminal tasks should not be submitted in reconciliation - Instance with only terminal tasks") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instance = TestInstanceBuilder.newBuilder(app.id)
      .addTaskError(containerName = Some("error"))
      .addTaskFailed(containerName = Some("failed"))
      .addTaskFinished(containerName = Some("finished"))
      .addTaskKilled(containerName = Some("killed"))
      .addTaskGone(containerName = Some("gone"))
      .addTaskDropped(containerName = Some("dropped"))
      .addTaskUnknown(containerName = Some("unknown"))
      .getInstance()

    appRepo.ids() returns Source.single(app.id)
    instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("nope".toPath, Seq(instance))))
    appRepo.get(app.id) returns Future.successful(Some(app))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      verify(driver, once).reconcileTasks(java.util.Arrays.asList())
      noMoreInteractions(driver)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Terminal tasks should not be submitted in reconciliation - Instance with all kind of tasks status") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instance = TestInstanceBuilder.newBuilder(app.id)
      .addTaskError(containerName = Some("error"))
      .addTaskFailed(containerName = Some("failed"))
      .addTaskFinished(containerName = Some("finished"))
      .addTaskKilled(containerName = Some("killed"))
      .addTaskGone(containerName = Some("gone"))
      .addTaskDropped(containerName = Some("dropped"))
      .addTaskUnknown(containerName = Some("unknown"))
      .addTaskReserved(containerName = Some("reserved"))
      .addTaskCreated(containerName = Some("created"))
      .addTaskKilling(containerName = Some("killing"))
      .addTaskRunning(containerName = Some("running"))
      .addTaskStaging(containerName = Some("staging"))
      .addTaskStarting(containerName = Some("starting"))
      .addTaskUnreachable(containerName = Some("unreachable"))
      .getInstance()

    appRepo.ids() returns Source.single(app.id)
    instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("nope".toPath, Seq(instance))))
    appRepo.get(app.id) returns Future.successful(Some(app))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      val nonTerminalTasks = instance.tasksMap.values.filter(!_.task.isTerminal)
      assert(nonTerminalTasks.size == 7, "We should have 7 non-terminal tasks")

      val expectedStatus: java.util.Collection[TaskStatus] = TaskStatusCollector.collectTaskStatusFor(Seq(instance))
      assert(expectedStatus.size() == 6, "We should have 6 task status, because Reserved do not have a mesosStatus")

      awaitAssert({
        driver.reconcileTasks(expectedStatus)
      }, 5.seconds, 10.millis)
      awaitAssert({
        driver.reconcileTasks(java.util.Arrays.asList())
      }, 5.seconds, 10.millis)
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("ScaleApps") {
    val f = new Fixture
    import f._
    val app: AppDefinition = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instances = Seq(TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance())

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    appRepo.ids() returns Source.single(app.id)
    instanceTracker.specInstancesSync(app.id) returns Seq.empty[Instance]
    instanceTracker.instancesBySpecSync returns InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances("nope".toPath, instances))
    instanceTracker.specInstancesSync("nope".toPath) returns instances
    appRepo.get(app.id) returns Future.successful(Some(app))
    instanceTracker.countLaunchedSpecInstancesSync(app.id) returns 0

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleRunSpecs

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
    appRepo.ids() returns Source.single(app.id)
    instanceTracker.specInstancesSync(app.id) returns Seq.empty[Instance]

    appRepo.get(app.id) returns Future.successful(Some(app))
    instanceTracker.countLaunchedSpecInstancesSync(app.id) returns 0

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleRunSpec("test-app".toPath)

      awaitAssert(verify(queue).add(app, 1), 5.seconds, 10.millis)

      expectMsg(5.seconds, RunSpecScaled(app.id))
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Kill tasks with scaling") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instance = TestInstanceBuilder.newBuilder(app.id).addTaskStaged().getInstance()
    // TODO(PODS): add proper way to create correct InstanceChanged event
    val instanceChangedEvent = InstanceChanged(
      instance.instanceId,
      instance.runSpecVersion,
      instance.runSpecId,
      Condition.Failed,
      instance
    )

    f.killService.customStatusUpdates.put(instance.instanceId, Seq(instanceChangedEvent))

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    appRepo.ids() returns Source.single(app.id)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq(instance)

    appRepo.get(app.id) returns (Future.successful(Some(app)), Future.successful(Some(app.copy(instances = 0))))
    instanceTracker.specInstancesSync(org.mockito.Matchers.eq(app.id)) returns Seq()

    appRepo.store(any) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Seq(instance))

      expectMsg(5.seconds, TasksKilled(app.id, Seq(instance.instanceId)))

      val Some(taskFailureEvent) = TaskFailure.FromInstanceChangedEvent(instanceChangedEvent)
      awaitAssert(verify(taskFailureEventRepository, times(1)).store(taskFailureEvent), 5.seconds, 10.millis)

      // KillTasks does no longer scale
      verify(appRepo, times(0)).store(any[AppDefinition])
    } finally {
      stopActor(schedulerActor)
    }
  }

  test("Kill tasks") {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = "/test-app".toPath, instances = 1)
    val instanceA = TestInstanceBuilder.newBuilderWithLaunchedTask(app.id).getInstance()

    queue.get(app.id) returns Some(LaunchQueueTestHelper.zeroCounts)
    appRepo.ids() returns Source.single(app.id)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq(instanceA)

    appRepo.get(app.id) returns (
      Future.successful(Some(app)),
      Future.successful(Some(app.copy(instances = 0))))
    instanceTracker.countLaunchedSpecInstancesSync(app.id) returns 0
    instanceTracker.specInstancesSync(org.mockito.Matchers.eq(app.id)) returns Seq()
    appRepo.store(any) returns Future.successful(Done)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Seq(instanceA))

      expectMsg(5.seconds, TasksKilled(app.id, List(instanceA.instanceId)))

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
    val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

    val appNew = app.copy(
      cmd = Some("cmd new"),
      versionInfo = VersionInfo.forNewConfig(Timestamp(1000))
    )

    val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(appNew.id -> appNew))))

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
    val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
    val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))
    val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"))))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    f.instanceTracker.specInstancesLaunchedSync(app.id) returns Seq(instance)
    f.instanceTracker.specInstances(org.mockito.Matchers.eq(app.id))(any[ExecutionContext]) returns Future.successful(Seq(instance))
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
    val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(createRootGroup(), rootGroup)

    appRepo.store(any) returns Future.successful(Done)
    appRepo.get(app.id) returns Future.successful(None)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq.empty[Instance]
    instanceTracker.specInstancesSync(app.id) returns Seq.empty[Instance]
    appRepo.delete(app.id) returns Future.successful(Done)

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
    val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(createRootGroup(), rootGroup)

    deploymentRepo.delete(any) returns Future.successful(Done)
    deploymentRepo.all() returns Source.single(plan)
    deploymentRepo.store(plan) returns Future.successful(Done)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq.empty[Instance]

    val schedulerActor = system.actorOf(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        deploymentRepo,
        hcManager,
        killService,
        queue,
        holder,
        electionService,
        system.eventStream
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
    val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

    val plan = DeploymentPlan(createRootGroup(), rootGroup)

    appRepo.store(any) returns Future.successful(Done)
    appRepo.get(app.id) returns Future.successful(None)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq.empty[Instance]
    appRepo.delete(app.id) returns Future.successful(Done)

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
  test("Cancellation timeout - this test is really racy and fails intermittently.", Unstable) {
    val f = new Fixture
    import f._
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5))
    val rootGroup = createRootGroup(Map(app.id -> app), groups = Set(createGroup(PathId("/foo/bar"))))

    val plan = DeploymentPlan(createRootGroup(), rootGroup)

    appRepo.store(any) returns Future.successful(Done)
    appRepo.get(app.id) returns Future.successful(None)
    instanceTracker.specInstancesLaunchedSync(app.id) returns Seq.empty[Instance]
    appRepo.delete(app.id) returns Future.successful(Done)

    val schedulerActor = TestActorRef[MarathonSchedulerActor](
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        deploymentRepo,
        hcManager,
        killService,
        queue,
        holder,
        electionService,
        system.eventStream
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
    appRepo.ids() returns Source.empty

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
    appRepo.ids() returns Source.empty

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
    val appRepo: AppRepository = mock[AppRepository]
    val podRepo: ReadOnlyPodRepository = mock[ReadOnlyPodRepository]
    val groupRepo: GroupRepository = mock[GroupRepository]
    val deploymentRepo: DeploymentRepository = mock[DeploymentRepository]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    val killService = new KillServiceMock(system)
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
        appRepo, podRepo, groupRepo, hcManager, instanceTracker, queue, new EventStream(system), ref, killService)(system.dispatcher, mat)
    }
    val conf: UpgradeConfig = mock[UpgradeConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    val deploymentManagerProps: SchedulerActions => Props = schedulerActions => Props(new DeploymentManager(
      instanceTracker,
      killService,
      queue,
      schedulerActions,
      storage,
      hcManager,
      system.eventStream,
      readinessCheckExecutor
    ))

    val historyActorProps: Props = Props(new HistoryActor(system.eventStream, taskFailureEventRepository))

    def createActor(overrideActions: Option[(ActorRef) => SchedulerActions] = None) = {
      val actions = overrideActions.getOrElse(schedulerActions)
      system.actorOf(
        MarathonSchedulerActor.props(
          actions,
          deploymentManagerProps,
          historyActorProps,
          deploymentRepo,
          hcManager,
          killService,
          queue,
          holder,
          electionService,
          system.eventStream
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
    appRepo.all() returns Source.empty
    podRepo.ids() returns Source.empty[PathId]
    groupRepo.root() returns Future.successful(createRootGroup())
    queue.get(any[PathId]) returns None
    instanceTracker.countLaunchedSpecInstancesSync(any[PathId]) returns 0
    conf.killBatchCycle returns 1.seconds
    conf.killBatchSize returns 100
  }

  implicit val defaultTimeout: Timeout = 30.seconds
}
