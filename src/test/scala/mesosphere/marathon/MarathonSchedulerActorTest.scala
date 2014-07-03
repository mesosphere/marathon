package mesosphere.marathon

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import akka.util.Timeout
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppRepository, PathId }
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import org.apache.mesos.Protos.TaskID
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

    verify(tracker).expunge("nope".toPath)
    verify(queue).add(app)
    verify(driver).killTask(TaskID.newBuilder().setValue("task_a").build())
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
}
