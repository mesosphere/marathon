package mesosphere.marathon

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ MesosStatusUpdateEvent, SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.same
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.Future

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
  var eventBus: EventStream = _

  val metricRegistry = new MetricRegistry

  before {
    repo = mock[AppRepository]
    hcManager = mock[HealthCheckManager]
    tracker = mock[TaskTracker]
    queue = spy(new TaskQueue)
    frameworkIdUtil = mock[FrameworkIdUtil]
    config = defaultConfig()
    taskIdUtil = TaskIdUtil
    probe = TestProbe()
    eventBus = system.eventStream
    scheduler = new MarathonScheduler(
      eventBus,
      new ObjectMapper,
      probe.ref,
      repo,
      hcManager,
      tracker,
      queue,
      frameworkIdUtil,
      taskIdUtil,
      mock[ActorSystem],
      config,
      new SchedulerCallbacks {
        override def disconnected(): Unit = {}
      }
    )
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("ResourceOffers") {
    val driver = mock[SchedulerDriver]
    val offer = makeBasicOffer(cpus = 4, mem = 1024, disk = 4000, beginPort = 31000, endPort = 32000).build
    val offers = Lists.newArrayList(offer)
    val now = Timestamp.now()
    val app = AppDefinition(
      id = "testOffers".toRootPath,
      executor = "//cmd",
      ports = Seq(8080),
      version = now
    )

    queue.add(app)

    when(tracker.checkStagedTasks).thenReturn(Seq())
    when(repo.currentAppVersions())
      .thenReturn(Future.successful(Map(app.id -> app.version)))

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

  test("Publishes event when registered") {
    val driver = mock[SchedulerDriver]
    val frameworkId = FrameworkID.newBuilder
      .setValue("some_id")
      .build()

    val masterInfo = MasterInfo.newBuilder()
      .setId("")
      .setIp(0)
      .setPort(5050)
      .setHostname("some_host")
      .build()

    eventBus.subscribe(probe.ref, classOf[SchedulerRegisteredEvent])

    scheduler.registered(driver, frameworkId, masterInfo)

    try {
      val msg = probe.expectMsgType[SchedulerRegisteredEvent]

      assert(msg.frameworkId == frameworkId.getValue)
      assert(msg.master == masterInfo.getHostname)
      assert(msg.eventType == "scheduler_registered_event")
    }
    finally {
      eventBus.unsubscribe(probe.ref)
    }
  }

  test("Publishes event when reregistered") {
    val driver = mock[SchedulerDriver]
    val masterInfo = MasterInfo.newBuilder()
      .setId("")
      .setIp(0)
      .setPort(5050)
      .setHostname("some_host")
      .build()

    eventBus.subscribe(probe.ref, classOf[SchedulerReregisteredEvent])

    scheduler.reregistered(driver, masterInfo)

    try {
      val msg = probe.expectMsgType[SchedulerReregisteredEvent]

      assert(msg.master == masterInfo.getHostname)
      assert(msg.eventType == "scheduler_reregistered_event")
    }
    finally {
      eventBus.unsubscribe(probe.ref)
    }
  }

  // regression test for bug described here: https://github.com/mesosphere/marathon/issues/995
  test("Does correctly extract app id from task id") {
    val app = AppDefinition(id = "/test/app".toRootPath)
    val taskId = "test_app.18462545-4ba2-12c5-65ba-37654cd26b63"
    val taskVersion = Timestamp.now().toString
    val driver = mock[SchedulerDriver]
    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(taskId))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = Protos.MarathonTask.newBuilder.setId(taskId).setVersion(taskVersion).build()
    for (_ <- 0 until 20) queue.rateLimiter.addDelay(app)

    when(tracker.fetchTask(app.id, taskId)).thenReturn(None)
    when(tracker.running(app.id, status)).thenReturn(Future.successful(task))
    when(repo.app(app.id, Timestamp(taskVersion))).thenReturn(Future.successful(Some(app)))

    eventBus.subscribe(probe.ref, classOf[MesosStatusUpdateEvent])

    scheduler.statusUpdate(driver, status)

    probe.expectMsgType[MesosStatusUpdateEvent]

    awaitAssert(queue.rateLimiter.getDelay(app).isOverdue())
  }

  // Currently does not work because of the injection used in MarathonScheduler.callbacks
  /*
  test("Publishes event when disconnected") {
    val driver = mock[SchedulerDriver]

    eventBus.subscribe(probe.ref, classOf[SchedulerDisconnectedEvent])

    scheduler.disconnected(driver)

    try {
      val msg = probe.expectMsgType[SchedulerDisconnectedEvent]

      assert(msg.eventType == "scheduler_reregistered_event")
    }
    finally {
      eventBus.unsubscribe(probe.ref)
    }
  }
  */
}
