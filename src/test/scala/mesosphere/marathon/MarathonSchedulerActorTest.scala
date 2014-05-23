package mesosphere.marathon

import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.actor.{Props, ActorRef, ActorSystem}
import org.scalatest.{Matchers, BeforeAndAfterAll}
import mesosphere.marathon.state.{Timestamp, AppRepository}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{TaskQueue, TaskTracker}
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.duration._
import akka.util.Timeout
import scala.concurrent.Future
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.mutable
import mesosphere.marathon.MarathonSchedulerActor.StopApp
import scala.Some
import mesosphere.marathon.MarathonSchedulerActor.AppStarted
import mesosphere.marathon.MarathonSchedulerActor.StartApp
import org.apache.mesos.Protos.TaskID
import mesosphere.marathon.api.v2.AppUpdate
import akka.testkit.TestActor.{NoAutoPilot, AutoPilot}
import mesosphere.marathon.upgrade.AppUpgradeManager.Upgrade

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
  var rateLimiters: RateLimiters = _
  var schedulerActor: TestActorRef[MarathonSchedulerActor] = _
  var driver: SchedulerDriver = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    MarathonSchedulerDriver.driver = Some(driver)
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    rateLimiters = mock[RateLimiters]
    schedulerActor = TestActorRef[MarathonSchedulerActor](Props(
      classOf[MarathonSchedulerActor],
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      rateLimiters,
      system.eventStream
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

  test("StartApp") {
    val app = AppDefinition(id = "testApp", instances = 2)

    when(repo.currentVersion(app.id)).thenReturn(Future.successful(None))
    when(repo.store(app)).thenReturn(Future.successful(Some(app)))
    when(tracker.get(app.id)).thenReturn(mutable.Set.empty[MarathonTask])
    when(tracker.count(app.id)).thenReturn(0)

    schedulerActor ! StartApp(app)

    expectMsg(5.seconds, AppStarted(app))

    verify(repo).currentVersion(app.id)
    verify(repo).store(app)
    verify(tracker).get(app.id)
    verify(tracker).count(app.id)
    verify(queue, times(2)).add(app)
  }

  test("StopApp") {
    val app = AppDefinition(id = "testApp", instances = 2)
    val task = MarathonTask.newBuilder().setId("task_1").build()

    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))
    when(tracker.get(app.id)).thenReturn(mutable.Set(task))

    schedulerActor ! StopApp(app)

    expectMsg(5.seconds, AppStopped(app))

    verify(repo).expunge(app.id)
    verify(hcManager).removeAllFor(app.id)
    verify(tracker).get(app.id)
    verify(driver).killTask(TaskID.newBuilder().setValue("task_1").build())
    verify(queue).purge(app)
    verify(tracker).shutDown(app.id)
  }

  test("UpdateApp") {
    val app = AppDefinition(id = "testApp", instances = 1, version = Timestamp(Timestamp.now().time.minusDays(1)))
    val appUpdate = spy(AppUpdate(instances = Some(2)))
    val updatedApp = appUpdate(app)

    doReturn(updatedApp).when(appUpdate).apply(app)
    when(repo.currentVersion(app.id)).thenReturn(Future.successful(Some(app)))
    when(repo.store(any())).thenReturn(Future.successful(Some(updatedApp)))

    schedulerActor ! UpdateApp(app.id, appUpdate)

    expectMsg(5.seconds, AppUpdated(app.id))

    verify(repo).currentVersion(app.id)
    verify(hcManager).reconcileWith(updatedApp)
    verify(repo).store(updatedApp)
  }

  test("UpgradeApp") {
    val app = AppDefinition(id = "testApp", instances = 1)
    val probe = TestProbe()
    schedulerActor.underlyingActor.upgradeManager = probe.ref

    when(repo.store(app)).thenReturn(Future.successful(Some(app)))

    probe.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any): AutoPilot = msg match {
        case Upgrade(_, `app`, 1) =>
          println("foo")
          sender ! true
          NoAutoPilot
        case _ => NoAutoPilot
      }
    })

    schedulerActor ! UpgradeApp(app, 1)

    expectMsg(5.seconds, AppUpgraded(app))
  }
}
