package mesosphere.marathon

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit, TestProbe }
import akka.util.Timeout
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ MesosStatusUpdateEvent, DeploymentSuccess, UpgradeEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.{ DeploymentStep, StopApplication, DeploymentPlan }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TaskID
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.mockito.Matchers.any
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.collection.immutable.Seq
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
  var schedulerActor: ActorRef = _
  var driver: SchedulerDriver = _
  var taskIdUtil: TaskIdUtil = _
  var storage: StorageProvider = _
  var taskFailureEventRepository: TaskFailureRepository = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    MarathonSchedulerDriver.driver = Some(driver)
    repo = mock[AppRepository]
    deploymentRepo = mock[DeploymentRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = spy(new TaskQueue)
    frameworkIdUtil = mock[FrameworkIdUtil]
    taskIdUtil = new TaskIdUtil
    storage = mock[StorageProvider]
    taskFailureEventRepository = mock[TaskFailureRepository]

    when(deploymentRepo.store(any())).thenAnswer(new Answer[Future[DeploymentPlan]] {
      override def answer(p1: InvocationOnMock): Future[DeploymentPlan] = {
        Future.successful(p1.getArguments()(0).asInstanceOf[DeploymentPlan])
      }
    })

    when(deploymentRepo.expunge(any())).thenReturn(Future.successful(Seq(true)))

    when(deploymentRepo.all()).thenReturn(Future.successful(Nil))

    when(repo.apps()).thenReturn(Future.successful(Nil))

    schedulerActor = system.actorOf(Props(
      classOf[MarathonSchedulerActor],
      new ObjectMapper(),
      repo,
      deploymentRepo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      storage,
      system.eventStream,
      taskFailureEventRepository,
      mock[MarathonConf]
    ))
  }

  after {
    watch(schedulerActor)
    system.stop(schedulerActor)
    expectTerminated(schedulerActor, 5.seconds)
  }

  override def afterAll(): Unit = {
    system.shutdown()
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

    schedulerActor ! ReconcileTasks

    expectMsg(5.seconds, TasksReconciled)

    awaitAssert({
      verify(tracker).shutdown("nope".toPath)
      verify(queue).add(app)
      verify(driver).killTask(TaskID("task_a"))
    }, 5.seconds, 10.millis)
  }

  test("ScaleApp") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)

    when(repo.allIds()).thenReturn(Future.successful(Seq(app.id.toString)))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])

    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(tracker.count(app.id)).thenReturn(0)

    schedulerActor ! ScaleApp("test-app".toPath)

    awaitAssert({
      verify(queue).add(app)
    }, 5.seconds, 10.millis)

    expectMsg(5.seconds, AppScaled(app.id))
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

    schedulerActor ! KillTasks(app.id, Set(taskA.getId), scale = true)
    schedulerActor ! KillTasks(app.id, Set(taskA.getId), scale = true)

    expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.getId)))
    expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.getId)))

    schedulerActor ! KillTasks(app.id, Set(taskA.getId), scale = true)

    expectMsg(5.seconds, TasksKilled(app.id, Set(taskA.getId)))

    val Some(taskFailureEvent) = TaskFailure.FromMesosStatusUpdateEvent(statusUpdateEvent)

    verify(taskFailureEventRepository, times(1)).store(app.id, taskFailureEvent)

    verify(repo, times(3)).store(app.copy(instances = 0))
  }

  test("Deployment") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = Some("cmd new"), version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, Nil, Timestamp.now())

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    schedulerActor ! Deploy(plan)

    expectMsg(DeploymentStarted(plan))

    val answer = probe.expectMsgType[DeploymentSuccess]
    answer.id should be(plan.id)

    system.eventStream.unsubscribe(probe.ref)
  }

  test("Deployment resets rate limiter for affected apps") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = Some("cmd new"), version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(StopApplication(app)))), Timestamp.now())

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    queue.rateLimiter.addDelay(app)

    schedulerActor ! Deploy(plan)

    expectMsg(DeploymentStarted(plan))

    awaitCond(queue.rateLimiter.getDelay(app).isOverdue(), 200.millis)

    system.eventStream.unsubscribe(probe.ref)
  }

  test("Deployment fail to acquire lock") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    when(repo.store(any())).thenReturn(Future.successful(app))
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(None))
    when(tracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.expunge(app.id)).thenReturn(Future.successful(Nil))

    schedulerActor ! Deploy(plan)

    expectMsgType[DeploymentStarted]

    schedulerActor ! Deploy(plan)

    val answer = expectMsgType[CommandFailed]

    answer.cmd should equal(Deploy(plan))
    answer.reason.isInstanceOf[AppLockedException] should be(true)
  }

  test("Restart deployments after failover") {
    val app = AppDefinition(id = PathId("app1"), cmd = Some("cmd"), instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val group = Group(PathId("/foo/bar"), Set(app))

    val plan = DeploymentPlan(Group.empty, group)

    deploymentRepo = mock[DeploymentRepository]

    when(deploymentRepo.expunge(any())).thenReturn(Future.successful(Seq(true)))

    when(deploymentRepo.all()).thenReturn(Future.successful(Seq(plan)))

    schedulerActor = system.actorOf(Props(
      classOf[MarathonSchedulerActor],
      new ObjectMapper(),
      repo,
      deploymentRepo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      storage,
      system.eventStream,
      taskFailureEventRepository,
      mock[MarathonConf]
    ))

    schedulerActor ! Deploy(plan)

    // This indicates that the deployment is already running,
    // which means it has successfully been restarted
    val answer = expectMsgType[CommandFailed]
    answer.cmd should equal(Deploy(plan))
    answer.reason.isInstanceOf[AppLockedException] should be(true)
  }
}
