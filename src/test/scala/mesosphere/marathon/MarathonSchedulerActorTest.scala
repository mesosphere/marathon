package mesosphere.marathon

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit, TestProbe }
import akka.util.Timeout
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event.{ DeploymentSuccess, UpgradeEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TaskID
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito._
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

class MarathonSchedulerActorTest extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers
    with ImplicitSender {

  var repo: AppRepository = _
  var hcManager: HealthCheckManager = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var schedulerActor: TestActorRef[MarathonSchedulerActor] = _
  var driver: SchedulerDriver = _
  var taskIdUtil: TaskIdUtil = _
  var storage: StorageProvider = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    MarathonSchedulerDriver.driver = Some(driver)
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    taskIdUtil = new TaskIdUtil
    storage = mock[StorageProvider]
    schedulerActor = TestActorRef[MarathonSchedulerActor](Props(
      classOf[MarathonSchedulerActor],
      new ObjectMapper(),
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      storage,
      system.eventStream,
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
    val tasks = mutable.Set(MarathonTask.newBuilder().setId("task_a").build())

    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(tracker.get(app.id)).thenReturn(mutable.Set.empty[MarathonTask])
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

    verify(tracker).shutdown("nope".toPath)
    verify(queue).add(app)
    verify(driver).killTask(TaskID("task_a"))
  }

  test("ScaleApp") {
    val app = AppDefinition(id = "test-app".toPath, instances = 1)

    when(repo.allIds()).thenReturn(Future.successful(Seq(app.id.toString)))
    when(tracker.get(app.id)).thenReturn(mutable.Set.empty[MarathonTask])

    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(tracker.count(app.id)).thenReturn(0)

    schedulerActor ! ScaleApp("test-app".toPath)
    verify(queue).add(app)

    expectMsg(5.seconds, AppScaled(app.id))
  }

  test("Deployment") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = "cmd", instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = "cmd new", version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, Nil, Timestamp.now())

    system.eventStream.subscribe(probe.ref, classOf[UpgradeEvent])

    schedulerActor ! Deploy(plan)

    expectMsg(DeploymentStarted(plan))

    val answer = probe.expectMsgType[DeploymentSuccess]
    answer.id should be(plan.id)

    system.eventStream.unsubscribe(probe.ref)
  }

  test("Deployment fail to acquire lock") {
    val probe = TestProbe()
    val app = AppDefinition(id = PathId("app1"), cmd = "cmd", instances = 2, upgradeStrategy = UpgradeStrategy(0.5), version = Timestamp(0))
    val origGroup = Group(PathId("/foo/bar"), Set(app))

    val appNew = app.copy(cmd = "cmd new", version = Timestamp(1000))

    val targetGroup = Group(PathId("/foo/bar"), Set(appNew))

    val plan = DeploymentPlan(origGroup, targetGroup)

    val lock = schedulerActor.underlyingActor.appLocks.get(app.id)
    lock.acquire()

    schedulerActor ! Deploy(plan)

    val answer = expectMsgType[CommandFailed]

    answer.cmd should equal(Deploy(plan))
    answer.reason.isInstanceOf[AppLockedException] should be(true)

    lock.release()
  }
}
