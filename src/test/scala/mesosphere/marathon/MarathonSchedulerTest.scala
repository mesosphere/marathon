package mesosphere.marathon

import org.mockito.Mockito._
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.state.{ Timestamp, AppRepository }
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import org.apache.mesos.SchedulerDriver
import com.google.common.collect.Lists
import org.apache.mesos.Protos.TaskID
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import mesosphere.marathon.MarathonSchedulerActor.LaunchTasks
import scala.concurrent.duration._

/**
  * @author Tobi Knaup
  */
class MarathonSchedulerTest extends TestKit(ActorSystem("System")) with MarathonSpec with BeforeAndAfterAll {

  var repo: AppRepository = _
  var hcManager: HealthCheckManager = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var scheduler: MarathonScheduler = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var rateLimiters: RateLimiters = _
  var probe: TestProbe = _

  before {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    rateLimiters = mock[RateLimiters]
    config = mock[MarathonConf]
    scheduler = new MarathonScheduler(
      mock[EventStream],
      new ObjectMapper,
      probe.ref,
      tracker,
      queue,
      frameworkIdUtil,
      rateLimiters
      mock[ActorSystem],
      config
    )
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("ResourceOffers") {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(4, 1024, 31000, 32000).build
    val offers = Lists.newArrayList(offer)
    val now = Timestamp.now
    val app = AppDefinition(
      id = "testOffers",
      executor = "//cmd",
      ports = Seq(8080),
      version = now
    )
    val allApps = Vector(app)

    when(tracker.newTaskId("testOffers"))
      .thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(app)
    when(queue.removeAll()).thenReturn(allApps)

    scheduler.resourceOffers(driver, offers)

    probe.expectMsgClass(5.seconds, classOf[LaunchTasks])
  }
}
