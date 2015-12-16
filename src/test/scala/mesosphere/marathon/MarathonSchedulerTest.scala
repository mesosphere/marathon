package mesosphere.marathon

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestKit, TestProbe }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskStatusEmitter
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.event.{ SchedulerDisconnectedEvent, SchedulerRegisteredEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks._
import mesosphere.util.state.{ MutableMesosLeaderInfo, MesosLeaderInfo, FrameworkIdUtil }
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.scalatest.BeforeAndAfterAll

class MarathonSchedulerTest extends TestKit(ActorSystem("System")) with MarathonSpec with BeforeAndAfterAll {

  var probe: TestProbe = _
  var repo: AppRepository = _
  var queue: LaunchQueue = _
  var scheduler: MarathonScheduler = _
  var frameworkIdUtil: FrameworkIdUtil = _
  var mesosLeaderInfo: MesosLeaderInfo = _
  var taskIdUtil: TaskIdUtil = _
  var config: MarathonConf = _
  var eventBus: EventStream = _
  var offerProcessor: OfferProcessor = _
  var taskStatusProcessor: TaskStatusUpdateProcessor = _

  before {
    repo = mock[AppRepository]
    queue = mock[LaunchQueue]
    frameworkIdUtil = mock[FrameworkIdUtil]
    mesosLeaderInfo = new MutableMesosLeaderInfo
    mesosLeaderInfo.onNewMasterInfo(MasterInfo.getDefaultInstance)
    config = defaultConfig(maxTasksPerOffer = 10)
    taskIdUtil = TaskIdUtil
    probe = TestProbe()
    eventBus = system.eventStream
    taskStatusProcessor = mock[TaskStatusUpdateProcessor]
    scheduler = new MarathonScheduler(
      eventBus,
      Clock(),
      offerProcessor = offerProcessor,
      taskStatusProcessor = taskStatusProcessor,
      frameworkIdUtil,
      mesosLeaderInfo,
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
      assert(mesosLeaderInfo.currentLeaderUrl.get == "http://some_host:5050/")
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
      assert(mesosLeaderInfo.currentLeaderUrl.get == "http://some_host:5050/")
    }
    finally {
      eventBus.unsubscribe(probe.ref)
    }
  }

  // Currently does not work because of the injection used in MarathonScheduler.callbacks
  test("Publishes event when disconnected") {
    val driver = mock[SchedulerDriver]

    eventBus.subscribe(probe.ref, classOf[SchedulerDisconnectedEvent])

    scheduler.disconnected(driver)

    try {
      val msg = probe.expectMsgType[SchedulerDisconnectedEvent]

      assert(msg.eventType == "scheduler_disconnected_event")
    }
    finally {
      eventBus.unsubscribe(probe.ref)
    }
  }
}
