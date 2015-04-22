package mesosphere.marathon.health

import akka.actor._
import akka.event.EventStream
import akka.testkit.EventFilter
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder, MarathonConf, MarathonSpec }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ AppDefinition, AppRepository, MarathonStore, PathId, Timestamp }
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.Logging
import org.apache.mesos.state.InMemoryState
import org.apache.mesos.{ Protos => mesos }
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf

import scala.concurrent.Await
import scala.concurrent.duration._

class MarathonHealthCheckManagerTest extends MarathonSpec with Logging {

  var hcManager: MarathonHealthCheckManager = _
  var taskTracker: TaskTracker = _
  var appRepository: AppRepository = _

  implicit var system: ActorSystem = _

  before {
    val registry = new MetricRegistry

    system = ActorSystem(
      "test-system",
      ConfigFactory.parseString(
        """akka.loggers = ["akka.testkit.TestEventListener"]"""
      )
    )

    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    taskTracker = new TaskTracker(new InMemoryState, defaultConfig(), registry)
    appRepository = new AppRepository(
      new MarathonStore[AppDefinition](config, new InMemoryState, registry, () => AppDefinition()),
      None,
      registry)

    hcManager = new MarathonHealthCheckManager(
      system,
      mock[MarathonScheduler],
      new MarathonSchedulerDriverHolder,
      mock[EventStream],
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

    taskTracker.created(appId, marathonTask)
    taskTracker.running(appId, taskStatus)

    taskId
  }

  def updateTaskHealth(taskId: mesos.TaskID, version: Timestamp, healthy: Boolean): Unit = {
    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(healthy)
      .build

    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
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

    taskTracker.created(appId, marathonTask)
    taskTracker.running(appId, taskStatus)

    hcManager.add(appId, version, healthCheck)

    val status1 = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(status1 == Seq(Health(taskId.getValue)))

    // send unhealthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, version)
    }

    val Seq(health2) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health2.lastFailure.isDefined)
    assert(health2.lastSuccess.isEmpty)

    // send healthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, version)
    }

    val Seq(health3) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health3.lastFailure.isDefined)
    assert(health3.lastSuccess.isDefined)
    assert(health3.lastSuccess > health3.lastFailure)
  }

  test("healthCounts") {
    val appId = "test".toRootPath
    val version = Timestamp(1024)
    appRepository.store(AppDefinition(id = appId, version = version))

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)
    hcManager.add(appId, version, healthCheck)

    val task1 = makeRunningTask(appId, version)
    val task2 = makeRunningTask(appId, version)
    val task3 = makeRunningTask(appId, version)

    var healthCounts = Await.result(hcManager.healthCounts(appId), 5.seconds)
    assert(healthCounts == HealthCounts(0, 3, 0))

    updateTaskHealth(task1, version, healthy = true)

    healthCounts = Await.result(hcManager.healthCounts(appId), 5.seconds)
    assert(healthCounts == HealthCounts(1, 2, 0))

    updateTaskHealth(task2, version, healthy = true)

    healthCounts = Await.result(hcManager.healthCounts(appId), 5.seconds)
    assert(healthCounts == HealthCounts(2, 1, 0))

    updateTaskHealth(task3, version, healthy = false)

    healthCounts = Await.result(hcManager.healthCounts(appId), 5.seconds)
    assert(healthCounts == HealthCounts(2, 0, 1))

    updateTaskHealth(task1, version, healthy = false)

    healthCounts = Await.result(hcManager.healthCounts(appId), 5.seconds)
    assert(healthCounts == HealthCounts(1, 0, 2))
  }

  test("statuses") {
    val appId = "test".toRootPath
    val version = Timestamp(1024)
    appRepository.store(AppDefinition(id = appId, version = version))

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)
    hcManager.add(appId, version, healthCheck)

    val task1 = makeRunningTask(appId, version)
    val task2 = makeRunningTask(appId, version)
    val task3 = makeRunningTask(appId, version)

    def statuses = Await.result(hcManager.statuses(appId), 5.seconds)

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
        .setState(mesos.TaskState.TASK_RUNNING)
        .setHealthy(false)
        .build
    val healthChecks = List(0, 1, 2).map { i =>
      (0 until i).map { j => HealthCheck(protocol = Protocol.COMMAND, gracePeriod = (i * 3 + j).seconds) }.toSet
    }
    val versions = List(0: Long, 1, 2).map { Timestamp(_) }.toArray
    val tasks = List(0, 1, 2).map { i =>
      MarathonTask.newBuilder
        .setId(TaskIdUtil.newTaskId(appId).getValue)
        .setVersion(versions(i).toString)
        .build
    }
    def startTask(appId: PathId, task: MarathonTask, version: Timestamp, healthChecks: Set[HealthCheck]) = {
      Await.result(appRepository.store(AppDefinition(
        id = appId,
        version = version,
        healthChecks = healthChecks
      )), 2.second)
      taskTracker.created(appId, task)
      Await.result(taskTracker.running(appId, taskStatus(task)), 2.second)
    }
    def startTask_i(i: Int): MarathonTask = startTask(appId, tasks(i), versions(i), healthChecks(i))
    def stopTask(appId: PathId, task: MarathonTask) =
      Await.result(taskTracker.terminated(appId, taskStatus(task, mesos.TaskState.TASK_FAILED)), 2.second)

    // one other task of another app
    val otherAppId = "other".toRootPath
    val otherTask = MarathonTask.newBuilder
      .setId(TaskIdUtil.newTaskId(appId).getValue)
      .setVersion(Timestamp(0).toString)
      .build
    val otherHealthChecks = Set(HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds))
    startTask(otherAppId, otherTask, Timestamp(42), otherHealthChecks)
    hcManager.addAllFor(Await.result(appRepository.currentVersion(otherAppId), 2.second).get)
    assert(hcManager.list(otherAppId) == otherHealthChecks)

    // start task 0 without running health check
    startTask_i(0)
    assert(hcManager.list(appId) == Set())

    // reconcileWith doesn't do anything b/c task 0 has no health checks
    hcManager.reconcileWith(appId)
    assert(hcManager.list(appId) == Set())

    // reconcileWith starts health checks of task 1
    assert(hcManager.list(appId) == Set())
    startTask_i(1)
    Await.result(hcManager.reconcileWith(appId), 2.second)
    assert(hcManager.list(appId) == healthChecks(1))

    // reconcileWith leaves health check running
    Await.result(hcManager.reconcileWith(appId), 2.second)
    assert(hcManager.list(appId) == healthChecks(1))

    // reconcileWith starts health checks of task 2 and leaves those of task 1 running
    startTask_i(2)
    Await.result(hcManager.reconcileWith(appId), 2.second)
    assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2))

    // reconcileWith stops health checks which are not current and which are without tasks
    stopTask(appId, tasks(1))
    assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2))
    Await.result(hcManager.reconcileWith(appId), 2.second)
    assert(hcManager.list(appId) == healthChecks(2))

    // reconcileWith leaves current version health checks running after termination
    stopTask(appId, tasks(2))
    assert(hcManager.list(appId) == healthChecks(2))
    Await.result(hcManager.reconcileWith(appId), 2.second)
    assert(hcManager.list(appId) == healthChecks(2))

    // other task was not touched
    assert(hcManager.list(otherAppId) == otherHealthChecks)
  }
}
