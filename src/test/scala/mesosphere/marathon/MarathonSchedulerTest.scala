package mesosphere.marathon

import java.util

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Lists
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppRepository, Timestamp }
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import org.apache.mesos.Protos.{ TaskInfo, TaskID }
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers.{ any, eq => mockEq }
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._

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
  var config: MarathonConf = _

  before {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    rateLimiters = mock[RateLimiters]
    config = mock[MarathonConf]
    probe = TestProbe()
    scheduler = new MarathonScheduler(
      mock[EventStream],
      new ObjectMapper,
      probe.ref,
      tracker,
      queue,
      frameworkIdUtil,
      rateLimiters,
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
    val now = Timestamp.now()
    val app = AppDefinition(
      id = "testOffers".toPath,
      executor = "//cmd",
      ports = Seq(8080),
      version = now
    )
    val allApps = Vector(app)

    when(tracker.newTaskId("testOffers".toPath))
      .thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(app)
    when(queue.removeAll()).thenReturn(allApps)

    scheduler.resourceOffers(driver, offers)

    verify(driver).launchTasks(mockEq(Seq(offer.getId).asJava), any[util.Collection[TaskInfo]]())
  }
}
