package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.testkit._
import akka.util.Timeout
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.event._
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan, DeploymentStep, StopApplication }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TaskID
import mesosphere.util.state.FrameworkIdUtil
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.collection.immutable.{ Seq, Set }
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

class MarathonSchedulerActorTest extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers
    with ImplicitSender {

  var repo: AppRepository = _
  var deploymentRepo: DeploymentRepository = _
  var hcManager: HealthCheckManager = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var driver: SchedulerDriver = _
  var holder: MarathonSchedulerDriverHolder = _
  var storage: StorageProvider = _
  var taskFailureEventRepository: TaskFailureRepository = _
  var leaderInfo: LeaderInfo = _
  var schedulerActions: ActorRef => SchedulerActions = _
  var deploymentManagerProps: SchedulerActions => Props = _
  var historyActorProps: Props = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    holder = new MarathonSchedulerDriverHolder
    holder.driver = Some(driver)
    repo = mock[AppRepository]
    deploymentRepo = mock[DeploymentRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = spy(new TaskQueue)
    frameworkIdUtil = mock[FrameworkIdUtil]
    storage = mock[StorageProvider]
    taskFailureEventRepository = mock[TaskFailureRepository]
    leaderInfo = mock[LeaderInfo]
    deploymentManagerProps = schedulerActions => Props(new DeploymentManager(
      repo,
      tracker,
      queue,
      schedulerActions,
      storage,
      hcManager,
      system.eventStream
    ))
    historyActorProps = Props(new HistoryActor(system.eventStream, taskFailureEventRepository))
    schedulerActions = ref => new SchedulerActions(
      repo, hcManager, tracker, queue, new EventStream(), ref, mock[MarathonConf])(system.dispatcher)

    when(deploymentRepo.store(any())).thenAnswer(new Answer[Future[DeploymentPlan]] {
      override def answer(p1: InvocationOnMock): Future[DeploymentPlan] = {
        Future.successful(p1.getArguments()(0).asInstanceOf[DeploymentPlan])
      }
    })

    when(deploymentRepo.expunge(any())).thenReturn(Future.successful(Seq(true)))

    when(deploymentRepo.all()).thenReturn(Future.successful(Nil))

    when(repo.apps()).thenReturn(Future.successful(Nil))
  }

  def createActor() = {
    system.actorOf(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        repo,
        deploymentRepo,
        hcManager,
        tracker,
        queue,
        holder,
        leaderInfo,
        system.eventStream
      )
    )
  }

  def stopActor(ref: ActorRef): Unit = {
    watch(ref)
    system.stop(ref)
    expectTerminated(ref)
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("RecoversDeploymentsAndReconcilesHealthChecksOnStart") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    when(repo.apps()).thenReturn(Future.successful(Seq(app)))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      awaitAssert({
        verify(hcManager).reconcileWith(app.id)
      }, 5.seconds, 10.millis)
      verify(deploymentRepo, times(1)).all()
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("ReconcileTasks") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    val tasks = Set(MarathonTask.newBuilder().setId("task_a").build())

    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(tracker.list).thenReturn(
      mutable.HashMap(
        PathId("nope") -> new TaskTracker.App(
          "nope".toPath,
          tasks,
          false)))
    when(tracker.get("nope".toPath)).thenReturn(tasks)
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(tracker.count(app.id)).thenReturn(0)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ReconcileTasks

      expectMsg(5.seconds, TasksReconciled)

      awaitAssert({
        verify(tracker).shutdown("nope".toPath)
        verify(driver).killTask(TaskID("task_a"))
      }, 5.seconds, 10.millis)
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("ScaleApps") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    val tasks = Set(MarathonTask.newBuilder().setId("task_a").build())

    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(tracker.list).thenReturn(
      mutable.HashMap(
        PathId("nope") -> new TaskTracker.App(
          "nope".toPath,
          tasks,
          false)))
    when(tracker.get("nope".toPath)).thenReturn(tasks)
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(tracker.count(app.id)).thenReturn(0)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleApps

      awaitAssert({
        verify(queue).add(app, 1)
      }, 5.seconds, 10.millis)
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("ScaleApp") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)

    when(repo.allIds()).thenReturn(Future.successful(Seq(app.id.toString)))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])

    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(tracker.count(app.id)).thenReturn(0)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! ScaleApp("test-app".toPath)

      awaitAssert({
        verify(queue).add(app, 1)
      }, 5.seconds, 10.millis)

      expectMsg(5.seconds, AppScaled(app.id))
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Kill tasks with scaling") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()

    when(repo.allIds()).thenReturn(Future.successful(Seq(app.id.toString)))
    when(tracker.get(app.id)).thenReturn(Set[MarathonTask](taskA))
    when(tracker.fetchTask(app.id, taskA.getId))
      .thenReturn(Some(taskA))
      .thenReturn(None)

    when(repo.currentVersion(app.id))
      .thenReturn(Future.successful(Some(app)))
      .thenReturn(Future.successful(Some(app.copy(instances = 0))))
    when(tracker.count(app.id)).thenReturn(0)
    when(repo.store(any())).thenReturn(Future.successful(app))

    val statusUpdateEvent = MesosStatusUpdateEvent("", taskA.getId, "TASK_FAILED", "", app.id, "", Nil, app.version.toString)

    when(driver.killTask(TaskID(taskA.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(statusUpdateEvent)
        Status.DRIVER_RUNNING
      }
    })

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA.getId))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.getId)))

      val Some(taskFailureEvent) = TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEvent)

      awaitAssert {
        verify(taskFailureEventRepository, times(1)).store(app.id, taskFailureEvent)
      }

      // KillTasks does no longer scale
      verify(repo, times(0)).store(any[AppDefinition]())
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Kill tasks") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()

    when(repo.allIds()).thenReturn(Future.successful(Seq(app.id.toString)))
    when(tracker.get(app.id)).thenReturn(Set[MarathonTask](taskA))
    when(tracker.fetchTask(app.id, taskA.getId))
      .thenReturn(Some(taskA))
      .thenReturn(None)

    when(repo.currentVersion(app.id))
      .thenReturn(Future.successful(Some(app)))
      .thenReturn(Future.successful(Some(app.copy(instances = 0))))
    when(tracker.count(app.id)).thenReturn(0)
    when(repo.store(any())).thenReturn(Future.successful(app))

    val statusUpdateEvent = MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString)

    when(driver.killTask(TaskID(taskA.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(statusUpdateEvent)
        Status.DRIVER_RUNNING
      }
    })

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! KillTasks(app.id, Set(taskA.getId))

      expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.getId)))

      awaitAssert(verify(queue).add(app, 1))
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Deployment") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = Some("cmd new"), version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

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
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Deployment resets rate limiter for affected apps") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val origGroup = Group(PathId("/foo/bar"), Set(app))
    val targetGroup = Group(PathId("/foo/bar"), Set())

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    when(tracker.get(app.id)).thenReturn(Set(taskA))
    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    queue.rateLimiter.addDelay(app)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      awaitAssert(verify(repo).store(app.copy(instances = 0)))

      system.eventStream.unsubscribe(probe.ref)
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Stopping an app sets instance count to 0 before removing the app completely") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val taskA = MarathonTask.newBuilder().setId("taskA_id").build()
    val taskB = MarathonTask.newBuilder().setId("taskB_id").build()
    val origGroup = Group(PathId("/foo/bar"), Set(app))
    val targetGroup = Group(PathId("/foo/bar"), Set())

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    when(tracker.get(app.id)).thenReturn(Set(taskA))
    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))

    when(driver.killTask(TaskID(taskA.getId))).thenAnswer(new Answer[Status] {
      def answer(invocation: InvocationOnMock): Status = {
        system.eventStream.publish(MesosStatusUpdateEvent("", taskA.getId, "TASK_KILLED", "", app.id, "", Nil, app.version.toString))
        Status.DRIVER_RUNNING
      }
    })

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    queue.rateLimiter.addDelay(app)

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsg(DeploymentStarted(plan))

      awaitCond(queue.rateLimiter.getDelay(app).isOverdue(), 200.millis)

      system.eventStream.unsubscribe(probe.ref)
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Deployment fail to acquire lock") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(None))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.expunge(app.id)).thenReturn(Future.successful(Nil))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted]

      schedulerActor ! Deploy(plan)

      val answer = expectMsgType[CommandFailed]

      answer.cmd should equal(Deploy(plan))
      answer.reason.isInstanceOf[AppLockedException] should be(true)
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Restart deployments after failover") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    deploymentRepo = mock[DeploymentRepository]

    when(deploymentRepo.expunge(any())).thenReturn(Future.successful(Seq(true)))

    when(deploymentRepo.all()).thenReturn(Future.successful(Seq(plan)))
    when(deploymentRepo.store(plan)).thenReturn(Future.successful(plan))

    val schedulerActor = system.actorOf(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        repo,
        deploymentRepo,
        hcManager,
        tracker,
        queue,
        holder,
        leaderInfo,
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
    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Forced deployment") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(None))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.expunge(app.id)).thenReturn(Future.successful(Nil))

    val schedulerActor = createActor()
    try {
      schedulerActor ! LocalLeadershipEvent.ElectedAsLeader
      schedulerActor ! Deploy(plan)

      expectMsgType[DeploymentStarted]

      schedulerActor ! Deploy(plan, force = true)

      val answer = expectMsgType[DeploymentStarted]

    }
    finally {
      stopActor(schedulerActor)
    }
  }

  test("Cancellation timeout") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(None))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.expunge(app.id)).thenReturn(Future.successful(Nil))

    val schedulerActor = TestActorRef(
      MarathonSchedulerActor.props(
        schedulerActions,
        deploymentManagerProps,
        historyActorProps,
        repo,
        deploymentRepo,
        hcManager,
        tracker,
        queue,
        holder,
        leaderInfo,
        system.eventStream,
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
    }
    finally {
      stopActor(schedulerActor)
    }
  }
}
