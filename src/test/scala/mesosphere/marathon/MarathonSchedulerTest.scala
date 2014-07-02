package mesosphere.marathon

import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppRepository, Timestamp }
import mesosphere.marathon.tasks._
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.{ RateLimiters, Stats }
import org.apache.mesos.Protos.{ OfferID, TaskID, TaskInfo }
import org.apache.mesos.SchedulerDriver
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.{ ArgumentCaptor, Matchers }

import scala.collection.JavaConverters._
import scala.collection.mutable

class MarathonSchedulerTest extends MarathonSpec {

  var repo: AppRepository = null
  var hcManager: HealthCheckManager = null
  var tracker: TaskTracker = null
  var queue: TaskQueue = null
  var scheduler: MarathonScheduler = null
  var frameworkIdUtil: FrameworkIdUtil = null
  var taskIdUtil: TaskIdUtil = null
  var rateLimiters: RateLimiters = null
  var config: MarathonConf = null

  val metricRegistry = new MetricRegistry
  val stats = new Stats(metricRegistry)

  before {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    taskIdUtil = mock[TaskIdUtil]
    rateLimiters = mock[RateLimiters]
    config = mock[MarathonConf]
    scheduler = new MarathonScheduler(
      None,
      new ObjectMapper,
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      rateLimiters,
      stats,
      config
    )
  }

  test("ResourceOffers") {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(cpus = 4, mem = 1024, disk = 4000, beginPort = 31000, endPort = 32000).build
    val offers = Lists.newArrayList(offer)
    val now = Timestamp.now
    val app = AppDefinition(
      id = "testOffers",
      executor = "//cmd",
      ports = Seq(8080),
      version = now
    )
    val allApps = Vector(app)

    when(taskIdUtil.newTaskId("testOffers"))
      .thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(app)
    when(queue.removeAll()).thenReturn(allApps)

    scheduler.resourceOffers(driver, offers)

    val offersCaptor = ArgumentCaptor.forClass(classOf[java.util.List[OfferID]])
    val taskInfosCaptor = ArgumentCaptor.forClass(classOf[java.util.List[TaskInfo]])
    val marathonTaskCaptor = ArgumentCaptor.forClass(classOf[MarathonTask])

    verify(driver).launchTasks(offersCaptor.capture(), taskInfosCaptor.capture())
    verify(tracker).created(same(app.id), marathonTaskCaptor.capture())

    assert(1 == offersCaptor.getValue.size())
    assert(offer.getId == offersCaptor.getValue.get(0))

    assert(1 == taskInfosCaptor.getValue.size())
    val taskInfoPortVar = taskInfosCaptor.getValue.get(0).getCommand.getEnvironment
      .getVariablesList.asScala.find(v => v.getName == "PORT")
    assert(taskInfoPortVar.isDefined)
    val marathonTaskPort = marathonTaskCaptor.getValue.getPorts(0)
    assert(taskInfoPortVar.get.getValue == marathonTaskPort.toString)
    val marathonTaskVersion = marathonTaskCaptor.getValue.getVersion
    assert(now.toString() == marathonTaskVersion)
  }

  test("ScaleDown") {
    val driver = mock[SchedulerDriver]
    val app = AppDefinition(
      id = "down",
      instances = 1
    )
    val task0 = MarathonTasks.makeTask("down_0", "localhost", Seq(), Seq(),
      Timestamp.now())
    val task1 = MarathonTasks.makeTask("down_1", "localhost", Seq(), Seq(),
      Timestamp.now())
    val tasks = new mutable.HashSet[MarathonTask]()
    tasks += task0
    tasks += task1
    val taken = new mutable.HashSet[MarathonTask]()
    taken += task0

    when(tracker.get(app.id)).thenReturn(tasks)
    when(tracker.count(app.id)).thenReturn(2)
    when(tracker.take(app.id, 1)).thenReturn(taken)

    scheduler.scale(driver, app)

    verify(queue).purge(same(app))
    verify(driver).killTask(Matchers.eq(TaskID.newBuilder.setValue("down_0").build))
  }
}
