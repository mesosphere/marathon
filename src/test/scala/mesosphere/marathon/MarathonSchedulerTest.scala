package mesosphere.marathon

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp }
import mesosphere.marathon.tasks.TaskQueue.QueuedTask
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.Protos.{ OfferID, TaskID, TaskInfo }
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.same
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.Deadline

class MarathonSchedulerTest extends TestKit(ActorSystem("System")) with MarathonSpec with BeforeAndAfterAll {

  var repo: AppRepository = _
  var hcManager: HealthCheckManager = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var scheduler: MarathonScheduler = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var probe: TestProbe = _
  var taskIdUtil: TaskIdUtil = _
  var config: MarathonConf = _

  val metricRegistry = new MetricRegistry

  before {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    config = defaultConfig()
    taskIdUtil = mock[TaskIdUtil]
    probe = TestProbe()
    scheduler = new MarathonScheduler(
      mock[EventStream],
      new ObjectMapper,
      probe.ref,
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      mock[ActorSystem],
      config
    )
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("ResourceOffers") {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(cpus = 4, mem = 1024, disk = 4000, beginPort = 31000, endPort = 32000).build
    val offers = Lists.newArrayList(offer)
    val now = Timestamp.now
    val app = AppDefinition(
      id = "testOffers".toRootPath,
      executor = "//cmd",
      ports = Seq(8080),
      version = now
    )
    val queuedTask = QueuedTask(app, Deadline.now)
    val list = Vector(queuedTask)
    val allApps = Vector(app)

    when(taskIdUtil.newTaskId("testOffers".toRootPath))
      .thenReturn(TaskID.newBuilder.setValue("testOffers_0-1234").build)
    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(queue.poll()).thenReturn(Some(queuedTask))
    when(queue.list).thenReturn(list)
    when(queue.removeAll()).thenReturn(list)
    when(queue.listApps).thenReturn(allApps)
    when(repo.currentAppVersions())
      .thenReturn(Future.successful(Map(app.id -> app.version)))

    scheduler.resourceOffers(driver, offers)

    val offersCaptor = ArgumentCaptor.forClass(classOf[java.util.List[OfferID]])
    val taskInfosCaptor = ArgumentCaptor.forClass(classOf[java.util.List[TaskInfo]])
    val marathonTaskCaptor = ArgumentCaptor.forClass(classOf[MarathonTask])

    verify(driver).launchTasks(offersCaptor.capture(), taskInfosCaptor.capture())
    verify(tracker).created(same(app.id), marathonTaskCaptor.capture())
    verify(queue).addAll(Seq.empty)

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
}
