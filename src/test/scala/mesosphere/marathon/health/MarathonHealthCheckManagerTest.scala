package mesosphere.marathon.health

import akka.actor._
import akka.event.EventStream
import akka.testkit.EventFilter
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ AppDefinition, AppRepository, MarathonStore, PathId, Timestamp }
import mesosphere.marathon.tasks._
import mesosphere.marathon.test.CaptureEvents
import mesosphere.marathon.{ MarathonConf, MarathonScheduler, MarathonSchedulerDriverHolder, MarathonSpec }
import mesosphere.util.Logging
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.{ Protos => mesos }
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class MarathonHealthCheckManagerTest extends MarathonSpec with ScalaFutures with Logging {

  var hcManager: MarathonHealthCheckManager = _
  var taskTracker: TaskTracker = _
  var taskCreator: TaskCreator = _
  var taskUpdater: TaskUpdater = _
  var appRepository: AppRepository = _
  var eventStream: EventStream = _

  implicit var system: ActorSystem = _

  before {
    val metrics = new Metrics(new MetricRegistry)

    system = ActorSystem(
      "test-system",
      ConfigFactory.parseString(
        """akka.loggers = ["akka.testkit.TestEventListener"]"""
      )
    )

    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    val taskTrackerImpl: TaskTrackerImpl = createTaskTracker()
    taskTracker = taskTrackerImpl
    taskCreator = taskTrackerImpl
    taskUpdater = taskTrackerImpl
    appRepository = new AppRepository(
      new MarathonStore[AppDefinition](new InMemoryStore, metrics, () => AppDefinition(), "app:"),
      None,
      metrics)

    eventStream = new EventStream()

    hcManager = new MarathonHealthCheckManager(
      system,
      mock[MarathonScheduler],
      new MarathonSchedulerDriverHolder,
      eventStream,
      taskTracker,
      appRepository
    )
  }

  after {
    system.shutdown()
  }

  def makeRunningTask(appId: PathId, version: Timestamp) = {
    val taskId = TaskIdUtil.newTaskId(appId)

    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .build

    val marathonTask = MarathonTask.newBuilder
      .setId(taskId.getValue)
      .setVersion(version.toString)
      .build

    taskCreator.created(appId, marathonTask).futureValue
    taskUpdater.statusUpdate(appId, taskStatus).futureValue

    taskId
  }

  def updateTaskHealth(taskId: mesos.TaskID, version: Timestamp, healthy: Boolean): Unit = {
    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(healthy)
      .build

    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus, version)
    }
  }

  test("Add") {
    val healthCheck = HealthCheck()
    val version = Timestamp(1024)
    hcManager.add("test".toRootPath, version, healthCheck)
    assert(hcManager.list("test".toRootPath).size == 1)
  }

  test("Update") {
    val appId = "test".toRootPath

    val taskId = TaskIdUtil.newTaskId(appId)

    val version = Timestamp(1024)

    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build

    val marathonTask = MarathonTask.newBuilder
      .setId(taskId.getValue)
      .setVersion(version.toString)
      .build

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)

    taskCreator.created(appId, marathonTask).futureValue
    taskUpdater.statusUpdate(appId, taskStatus).futureValue

    hcManager.add(appId, version, healthCheck)

    val status1 = hcManager.status(appId, taskId.getValue).futureValue
    assert(status1 == Seq(Health(taskId.getValue)))

    // send unhealthy task status
    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, version)
    }

    val Seq(health2) = hcManager.status(appId, taskId.getValue).futureValue
    assert(health2.lastFailure.isDefined)
    assert(health2.lastSuccess.isEmpty)

    // send healthy task status
    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, version)
    }

    val Seq(health3) = hcManager.status(appId, taskId.getValue).futureValue
    assert(health3.lastFailure.isDefined)
    assert(health3.lastSuccess.isDefined)
    assert(health3.lastSuccess > health3.lastFailure)
  }

  test("statuses") {
    val appId = "test".toRootPath
    val app: AppDefinition = AppDefinition(id = appId)
    appRepository.store(app)
    val version = app.version

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)
    hcManager.add(appId, version, healthCheck)

    val task1 = makeRunningTask(appId, version)
    val task2 = makeRunningTask(appId, version)
    val task3 = makeRunningTask(appId, version)

    def statuses = hcManager.statuses(appId).futureValue

    statuses.foreach {
      case (_, health) => assert(health.isEmpty)
    }

    updateTaskHealth(task1, version, healthy = true)
    statuses.foreach {
      case (id, health) if id == task1.getValue =>
        assert(health.size == 1)
        assert(health.head.alive)
      case (_, health) => assert(health.isEmpty)
    }

    updateTaskHealth(task2, version, healthy = true)
    statuses.foreach {
      case (id, health) if id == task3.getValue =>
        assert(health.isEmpty)
      case (_, health) =>
        assert(health.size == 1)
        assert(health.head.alive)
    }

    updateTaskHealth(task3, version, healthy = false)
    statuses.foreach {
      case (id, health) if id == task3.getValue =>
        assert(health.size == 1)
        assert(!health.head.alive)
      case (_, health) =>
        assert(health.size == 1)
        assert(health.head.alive)
    }

    updateTaskHealth(task1, version, healthy = false)
    statuses.foreach {
      case (id, health) if id == task2.getValue =>
        assert(health.size == 1)
        assert(health.head.alive)
      case (_, health) =>
        assert(health.size == 1)
        assert(!health.head.alive)
    }
  }

  test("reconcileWith") {
    val appId = "test".toRootPath
    def taskStatus(task: MarathonTask, state: mesos.TaskState = mesos.TaskState.TASK_RUNNING) =
      mesos.TaskStatus.newBuilder
        .setTaskId(mesos.TaskID.newBuilder()
          .setValue(task.getId)
          .build)
        .setState(state)
        .setHealthy(false)
        .build
    val healthChecks = List(0, 1, 2).map { i =>
      (0 until i).map { j => HealthCheck(protocol = Protocol.COMMAND, gracePeriod = (i * 3 + j).seconds) }.toSet
    }
    val versions = List(0L, 1L, 2L).map { Timestamp(_) }.toArray
    val tasks = List(0, 1, 2).map { i =>
      MarathonTask.newBuilder
        .setId(TaskIdUtil.newTaskId(appId).getValue)
        .setVersion(versions(i).toString)
        .build
    }
    def startTask(appId: PathId, task: MarathonTask, version: Timestamp, healthChecks: Set[HealthCheck]) = {
      appRepository.store(AppDefinition(
        id = appId,
        versionInfo = AppDefinition.VersionInfo.forNewConfig(version),
        healthChecks = healthChecks
      )).futureValue
      taskCreator.created(appId, task).futureValue
      taskUpdater.statusUpdate(appId, taskStatus(task)).futureValue
    }
    def startTask_i(i: Int): Unit = startTask(appId, tasks(i), versions(i), healthChecks(i))
    def stopTask(appId: PathId, task: MarathonTask) =
      taskCreator.terminated(appId, task.getId).futureValue

    // one other task of another app
    val otherAppId = "other".toRootPath
    val otherTask = MarathonTask.newBuilder
      .setId(TaskIdUtil.newTaskId(appId).getValue)
      .setVersion(Timestamp(0).toString)
      .build
    val otherHealthChecks = Set(HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds))
    startTask(otherAppId, otherTask, Timestamp(42), otherHealthChecks)
    hcManager.addAllFor(appRepository.currentVersion(otherAppId).futureValue.get)
    assert(hcManager.list(otherAppId) == otherHealthChecks)

    // start task 0 without running health check
    startTask_i(0)
    assert(hcManager.list(appId) == Set())

    // reconcileWith doesn't do anything b/c task 0 has no health checks
    hcManager.reconcileWith(appId)
    assert(hcManager.list(appId) == Set())

    // reconcileWith starts health checks of task 1
    val captured1 = captureEvents.forBlock {
      assert(hcManager.list(appId) == Set())
      startTask_i(1)
      hcManager.reconcileWith(appId).futureValue
    }
    assert(captured1.map(_.eventType) == Vector("add_health_check_event"))
    assert(hcManager.list(appId) == healthChecks(1))

    // reconcileWith leaves health check running
    val captured2 = captureEvents.forBlock {
      hcManager.reconcileWith(appId).futureValue
    }
    assert(captured2.isEmpty)
    assert(hcManager.list(appId) == healthChecks(1))

    // reconcileWith starts health checks of task 2 and leaves those of task 1 running
    val captured3 = captureEvents.forBlock {
      startTask_i(2)
      hcManager.reconcileWith(appId).futureValue
    }
    assert(captured3.map(_.eventType) == Vector("add_health_check_event", "add_health_check_event"))
    assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2))

    // reconcileWith stops health checks which are not current and which are without tasks
    val captured4 = captureEvents.forBlock {
      stopTask(appId, tasks(1))
      assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2))
      hcManager.reconcileWith(appId).futureValue
    }
    assert(captured4.map(_.eventType) == Vector("remove_health_check_event"))
    assert(hcManager.list(appId) == healthChecks(2))

    // reconcileWith leaves current version health checks running after termination
    val captured5 = captureEvents.forBlock {
      stopTask(appId, tasks(2))
      assert(hcManager.list(appId) == healthChecks(2))
      hcManager.reconcileWith(appId).futureValue
    }
    assert(captured5.map(_.eventType) == Vector.empty)
    assert(hcManager.list(appId) == healthChecks(2))

    // other task was not touched
    assert(hcManager.list(otherAppId) == otherHealthChecks)
  }

  def captureEvents = new CaptureEvents(eventStream)
}
