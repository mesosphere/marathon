package mesosphere.marathon

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.junit.{Before, Test}
import org.junit.Assert._
import org.mockito.Mockito._
import org.mockito.Matchers._
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.state.{Timestamp, MarathonStore, AppRepository}
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{TaskQueue, TaskTracker}
import org.apache.mesos.SchedulerDriver
import com.google.common.collect.Lists
import org.apache.mesos.Protos.{OfferID, TaskID, TaskInfo}
import org.mockito.ArgumentCaptor
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.JavaConverters._
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters
import java.util.concurrent.LinkedBlockingQueue

/**
 * @author Tobi Knaup
 */
class MarathonSchedulerTest extends AssertionsForJUnit
  with MockitoSugar with MarathonTestHelper {

  var repo: AppRepository = null
  var hcManager: HealthCheckManager = null
  var tracker: TaskTracker = null
  var queue: TaskQueue = null
  var scheduler: MarathonScheduler = null
  var frameworkIdUtil: FrameworkIdUtil = null
  var rateLimiters: RateLimiters = null

  @Before
  def setupScheduler() = {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    rateLimiters = mock[RateLimiters]
    scheduler = new MarathonScheduler(
      None,
      new ObjectMapper,
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      rateLimiters
    )
  }

  @Test
  def testResourceOffers() {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(4, 1024, 31000, 32000).build
    val offers = Lists.newArrayList(offer)
    val now = Timestamp.now
    val app = AppDefinition(
      id = "testOffers",
      executor = "//cmd",
      ports = Seq(31080),
      version = now
    )
    val allApps = Vector(app)

    when(tracker.newTaskId("testOffers")).thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(app)
    when(queue.removeAll()).thenReturn(allApps)

    scheduler.resourceOffers(driver, offers)

    val offersCaptor = ArgumentCaptor.forClass(classOf[java.util.List[OfferID]])
    val taskInfosCaptor = ArgumentCaptor.forClass(classOf[java.util.List[TaskInfo]])
    val marathonTaskCaptor = ArgumentCaptor.forClass(classOf[MarathonTask])

    verify(driver).launchTasks(offersCaptor.capture(), taskInfosCaptor.capture())
    verify(tracker).starting(same(app.id), marathonTaskCaptor.capture())

    assertEquals(1, offersCaptor.getValue.size())
    assertEquals(offer.getId, offersCaptor.getValue.get(0))

    assertEquals(1, taskInfosCaptor.getValue.size())
    val taskInfoPortVar = taskInfosCaptor.getValue.get(0).getCommand.getEnvironment
      .getVariablesList.asScala.find(v => v.getName == "PORT")
    assertTrue(taskInfoPortVar.isDefined)
    val marathonTaskPort = marathonTaskCaptor.getValue.getPorts(0)
    assertEquals(taskInfoPortVar.get.getValue, marathonTaskPort.toString)
    val marathonTaskVersion = marathonTaskCaptor.getValue.getVersion
    assertEquals(now.toString(), marathonTaskVersion)
  }
}
