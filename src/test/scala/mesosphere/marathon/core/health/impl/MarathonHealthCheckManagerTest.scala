package mesosphere.marathon.core.health.impl

import akka.actor._
import akka.event.EventStream
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.EventFilter
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon._
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.health.{ Health, HealthCheck }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.leadership.{ AlwaysElectedLeadershipModule, LeadershipModule }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.core.task.tracker.{ InstanceCreationHandler, InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.AppRepository
import mesosphere.marathon.test.{ CaptureEvents, MarathonShutdownHookSupport }
import mesosphere.util.Logging
import org.apache.mesos.{ Protos => mesos }
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Span }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class MarathonHealthCheckManagerTest
    extends MarathonSpec with ScalaFutures with Logging with MarathonShutdownHookSupport {

  var hcManager: MarathonHealthCheckManager = _
  var taskTracker: InstanceTracker = _
  var taskCreationHandler: InstanceCreationHandler = _
  var stateOpProcessor: TaskStateOpProcessor = _
  var appRepository: AppRepository = _
  var eventStream: EventStream = _

  implicit var system: ActorSystem = _
  implicit var mat: Materializer = _
  var leadershipModule: LeadershipModule = _

  val appId = "test".toRootPath
  val clock = ConstantClock()

  before {
    implicit val metrics = new Metrics(new MetricRegistry)

    system = ActorSystem(
      "test-system",
      ConfigFactory.parseString(
        """akka.loggers = ["akka.testkit.TestEventListener"]"""
      )
    )
    mat = ActorMaterializer()
    leadershipModule = AlwaysElectedLeadershipModule(shutdownHooks)

    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
      verify()
    }

    val taskTrackerModule = MarathonTestHelper.createTaskTrackerModule(leadershipModule)
    taskTracker = taskTrackerModule.taskTracker
    taskCreationHandler = taskTrackerModule.taskCreationHandler
    stateOpProcessor = taskTrackerModule.stateOpProcessor

    val store = new InMemoryPersistenceStore()(ctx = ExecutionContext.global, mat = mat, metrics = metrics)
    appRepository = AppRepository.inMemRepository(store)(ExecutionContext.global)

    eventStream = new EventStream(system)

    val killService = mock[TaskKillService]
    hcManager = new MarathonHealthCheckManager(
      system,
      killService,
      eventStream,
      taskTracker,
      appRepository,
      config
    )
  }

  def makeRunningTask(appId: PathId, version: Timestamp) = {
    val taskId = Task.Id.forRunSpec(appId)

    val taskStatus = MarathonTestHelper.runningTask(taskId.idString).launched.get.status.mesosStatus.get
    val marathonTask = MarathonTestHelper.stagedTask(taskId.idString, appVersion = version)
    val update = InstanceUpdateOperation.MesosUpdate(marathonTask, taskStatus, clock.now())

    taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(marathonTask)).futureValue
    stateOpProcessor.process(update).futureValue

    taskId
  }

  def updateTaskHealth(taskId: Task.Id, version: Timestamp, healthy: Boolean): Unit = {
    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(healthy)
      .build

    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus, version)
    }
  }

  test("Add for a known app") {
    val app: AppDefinition = AppDefinition(id = appId)
    appRepository.store(app).futureValue

    val healthCheck = HealthCheck()
    hcManager.add(app, healthCheck)
    assert(hcManager.list(appId).size == 1)
  }

  test("Add for not-yet-known app") {
    val app: AppDefinition = AppDefinition(id = appId)

    val healthCheck = HealthCheck()
    hcManager.add(app, healthCheck)
    assert(hcManager.list(appId).size == 1)
  }

  test("Update") {
    val app: AppDefinition = AppDefinition(id = appId)
    appRepository.store(app).futureValue

    val taskId = Task.Id.forRunSpec(appId)

    val taskStatus = MarathonTestHelper.unhealthyTask(taskId.idString).launched.get.status.mesosStatus.get
    val marathonTask = MarathonTestHelper.stagedTask(taskId.idString, appVersion = app.version)
    val update = InstanceUpdateOperation.MesosUpdate(marathonTask, taskStatus, clock.now())

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)

    taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(marathonTask)).futureValue
    stateOpProcessor.process(update).futureValue

    hcManager.add(app, healthCheck)

    val status1 = hcManager.status(appId, taskId).futureValue
    assert(status1 == Seq(Health(taskId)))

    // send unhealthy task status
    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, app.version)
    }

    val Seq(health2) = hcManager.status(appId, taskId).futureValue
    assert(health2.lastFailure.isDefined)
    assert(health2.lastSuccess.isEmpty)

    // send healthy task status
    EventFilter.info(start = "Received health result for app", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, app.version)
    }

    val Seq(health3) = hcManager.status(appId, taskId).futureValue
    assert(health3.lastFailure.isDefined)
    assert(health3.lastSuccess.isDefined)
    assert(health3.lastSuccess > health3.lastFailure)
  }

  test("statuses") {
    val app: AppDefinition = AppDefinition(id = appId)
    appRepository.store(app).futureValue
    val version = app.version

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)
    hcManager.add(app, healthCheck)

    val task1 = makeRunningTask(appId, version)
    val task2 = makeRunningTask(appId, version)
    val task3 = makeRunningTask(appId, version)

    def statuses = hcManager.statuses(appId).futureValue

    statuses.foreach {
      case (_, health) => assert(health.isEmpty)
    }

    updateTaskHealth(task1, version, healthy = true)
    statuses.foreach {
      case (id, health) if id == task1 =>
        assert(health.size == 1)
        assert(health.head.alive)
      case (_, health) => assert(health.isEmpty)
    }

    updateTaskHealth(task2, version, healthy = true)
    statuses.foreach {
      case (id, health) if id == task3 =>
        assert(health.isEmpty)
      case (_, health) =>
        assert(health.size == 1)
        assert(health.head.alive)
    }

    updateTaskHealth(task3, version, healthy = false)
    statuses.foreach {
      case (id, health) if id == task3 =>
        assert(health.size == 1)
        assert(!health.head.alive)
      case (_, health) =>
        assert(health.size == 1)
        assert(health.head.alive)
    }

    updateTaskHealth(task1, version, healthy = false)
    statuses.foreach {
      case (id, health) if id == task2 =>
        assert(health.size == 1)
        assert(health.head.alive)
      case (_, health) =>
        assert(health.size == 1)
        assert(!health.head.alive)
    }
  }

  test("reconcileWith") {
    def taskStatus(task: Task, state: mesos.TaskState = mesos.TaskState.TASK_RUNNING) =
      mesos.TaskStatus.newBuilder
        .setTaskId(mesos.TaskID.newBuilder()
          .setValue(task.taskId.idString)
          .build)
        .setState(state)
        .setHealthy(false)
        .build
    val healthChecks = List(0, 1, 2).map { i =>
      (0 until i).map { j => HealthCheck(protocol = Protocol.COMMAND, gracePeriod = (i * 3 + j).seconds) }.toSet
    }
    val versions = List(0L, 1L, 2L).map { Timestamp(_) }.toArray
    val tasks = List(0, 1, 2).map { i =>
      MarathonTestHelper.stagedTaskForApp(appId, appVersion = versions(i))
    }
    def startTask(appId: PathId, task: Task, version: Timestamp, healthChecks: Set[HealthCheck]) = {
      appRepository.store(AppDefinition(
        id = appId,
        versionInfo = VersionInfo.forNewConfig(version),
        healthChecks = healthChecks
      )).futureValue
      taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(task)).futureValue
      val update = InstanceUpdateOperation.MesosUpdate(task, taskStatus(task), clock.now())
      stateOpProcessor.process(update).futureValue
    }
    def startTask_i(i: Int): Unit = startTask(appId, tasks(i), versions(i), healthChecks(i))
    def stopTask(appId: PathId, task: Task) =
      taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(task.taskId)).futureValue

    // one other task of another app
    val otherAppId = "other".toRootPath
    val otherTask = MarathonTestHelper.stagedTaskForApp(appId, appVersion = Timestamp(0))
    val otherHealthChecks = Set(HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds))
    startTask(otherAppId, otherTask, Timestamp(42), otherHealthChecks)
    hcManager.addAllFor(appRepository.get(otherAppId).futureValue.get)
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

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(1000, Millis)))
}
