package mesosphere.marathon
package core.health.impl

import akka.event.EventStream
import com.typesafe.config.{ Config, ConfigFactory }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheck, MesosCommandHealthCheck }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.leadership.{ AlwaysElectedLeadershipModule, LeadershipModule }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{ InstanceCreationHandler, InstanceTracker, InstanceTrackerModule, TaskStateOpProcessor }
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ CaptureEvents, MarathonTestHelper, SettableClock }
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.concurrent.Eventually

import scala.collection.immutable.Set
import scala.concurrent.Future
import scala.concurrent.duration._

class MarathonHealthCheckManagerTest extends AkkaUnitTest with Eventually {

  override protected lazy val akkaConfig: Config = ConfigFactory.parseString(
    """akka.loggers = ["akka.testkit.TestEventListener"]"""
  )

  private val appId = "test".toRootPath
  private val clock = new SettableClock()

  case class Fixture() {
    val leadershipModule: LeadershipModule = AlwaysElectedLeadershipModule.forRefFactory(system)
    val taskTrackerModule: InstanceTrackerModule = MarathonTestHelper.createTaskTrackerModule(leadershipModule)
    val taskTracker: InstanceTracker = taskTrackerModule.instanceTracker
    implicit val taskCreationHandler: InstanceCreationHandler = taskTrackerModule.instanceCreationHandler
    implicit val stateOpProcessor: TaskStateOpProcessor = taskTrackerModule.stateOpProcessor
    val groupManager: GroupManager = mock[GroupManager]
    implicit val eventStream: EventStream = new EventStream(system)
    val killService: KillService = mock[KillService]
    implicit val hcManager: MarathonHealthCheckManager = new MarathonHealthCheckManager(
      system,
      killService,
      eventStream,
      taskTracker,
      groupManager
    )
  }

  def makeRunningTask(appId: PathId, version: Timestamp)(implicit taskCreationHandler: InstanceCreationHandler, stateOpProcessor: TaskStateOpProcessor): (Instance.Id, Task.Id) = {
    val instance = TestInstanceBuilder.newBuilder(appId, version = version).addTaskStaged().getInstance()
    val (taskId, _) = instance.tasksMap.head
    val taskStatus = TestTaskBuilder.Helper.runningTask(taskId).status.mesosStatus.get
    val update = InstanceUpdateOperation.MesosUpdate(instance, taskStatus, clock.now())

    taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(instance)).futureValue
    stateOpProcessor.process(update).futureValue

    (instance.instanceId, taskId)
  }

  def updateTaskHealth(taskId: Task.Id, version: Timestamp, healthy: Boolean)(implicit hcManager: MarathonHealthCheckManager): Unit = {
    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(healthy)
      .build

    hcManager.update(taskStatus, version)
  }

  "HealthCheckManager" should {
    "add for a known app" in new Fixture {
      val app: AppDefinition = AppDefinition(id = appId)

      val healthCheck = MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true"))
      hcManager.add(app, healthCheck, Seq.empty)
      assert(hcManager.list(appId).size == 1)
    }

    "add for not-yet-known app" in new Fixture {
      val app: AppDefinition = AppDefinition(id = appId)

      val healthCheck = MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true"))
      hcManager.add(app, healthCheck, Seq.empty)
      assert(hcManager.list(appId).size == 1)
    }

    "update" in new Fixture {
      val app: AppDefinition = AppDefinition(id = appId, versionInfo = VersionInfo.NoVersion)

      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
      val instanceId = instance.instanceId
      val (taskId, _) = instance.tasksMap.head
      val taskStatus = TestTaskBuilder.Helper.unhealthyTask(taskId).status.mesosStatus.get
      val update = InstanceUpdateOperation.MesosUpdate(instance, taskStatus, clock.now())

      val healthCheck = MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true"))

      taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(instance)).futureValue
      stateOpProcessor.process(update).futureValue

      hcManager.add(app, healthCheck, Seq.empty)

      val status1 = hcManager.status(appId, instanceId).futureValue
      assert(status1 == Seq(Health(instanceId)))

      // send unhealthy task status
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, app.version)

      eventually {
        val Seq(health2) = hcManager.status(appId, instanceId).futureValue
        assert(health2.lastFailure.isDefined)
        assert(health2.lastSuccess.isEmpty)
      }

      // send healthy task status
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, app.version)

      eventually {
        val Seq(health3) = hcManager.status(appId, instanceId).futureValue
        assert(health3.lastFailure.isDefined)
        assert(health3.lastSuccess.isDefined)
        assert(health3.lastSuccess > health3.lastFailure)
      }
    }

    "statuses" in new Fixture {
      val app: AppDefinition = AppDefinition(id = appId)
      val version = app.version

      val healthCheck = MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true"))
      hcManager.add(app, healthCheck, Seq.empty)

      val (instanceId1, taskId1) = makeRunningTask(appId, version)
      val (instanceId2, taskId2) = makeRunningTask(appId, version)
      val (instanceId3, taskId3) = makeRunningTask(appId, version)

      def statuses = hcManager.statuses(appId).futureValue

      statuses.foreach {
        case (_, health) => assert(health.isEmpty)
      }

      updateTaskHealth(taskId1, version, healthy = true)
      eventually {
        statuses.foreach {
          case (id, health) if id == instanceId1 =>
            assert(health.size == 1)
            assert(health.head.alive)
          case (_, health) => assert(health.isEmpty)
        }
      }

      updateTaskHealth(taskId2, version, healthy = true)
      eventually {
        statuses.foreach {
          case (id, health) if id == instanceId3 =>
            assert(health.isEmpty)
          case (_, health) =>
            assert(health.size == 1)
            assert(health.head.alive)
        }
      }

      updateTaskHealth(taskId3, version, healthy = false)
      eventually {
        statuses.foreach {
          case (id, health) if id == instanceId3 =>
            assert(health.size == 1)
            assert(!health.head.alive)
          case (_, health) =>
            assert(health.size == 1)
            assert(health.head.alive)
        }
      }

      updateTaskHealth(taskId1, version, healthy = false)
      eventually {
        statuses.foreach {
          case (id, health) if id == instanceId2 =>
            assert(health.size == 1)
            assert(health.head.alive)
          case (_, health) =>
            assert(health.size == 1)
            assert(!health.head.alive)
        }
      }
    }

    "reconcile" in new Fixture {
      def taskStatus(instance: Instance, state: mesos.TaskState = mesos.TaskState.TASK_RUNNING) =
        mesos.TaskStatus.newBuilder
          .setTaskId(mesos.TaskID.newBuilder()
            .setValue(instance.tasksMap.keys.head.idString)
            .build)
          .setState(state)
          .setHealthy(true)
          .build

      val healthChecks = List(0, 1, 2).map { i =>
        (0 until i).map { j =>
          val check: HealthCheck = MesosCommandHealthCheck(gracePeriod = (i * 3 + j).seconds, command = Command("true"))
          check
        }.to[Set]
      }
      val versions = List(0L, 1L, 2L).map {
        Timestamp(_)
      }.toArray
      val instances = List(0, 1, 2).map { i =>
        TestInstanceBuilder.newBuilder(appId, version = versions(i)).addTaskStaged(version = Some(versions(i))).getInstance()
      }

      def startTask(appId: PathId, instance: Instance, version: Timestamp, healthChecks: Set[HealthCheck]): AppDefinition = {
        val app = AppDefinition(
          id = appId,
          versionInfo = VersionInfo.forNewConfig(version),
          healthChecks = healthChecks
        )
        taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(instance)).futureValue
        val update = InstanceUpdateOperation.MesosUpdate(instance, taskStatus(instance), clock.now())
        stateOpProcessor.process(update).futureValue
        app
      }

      def startTask_i(i: Int): AppDefinition = startTask(appId, instances(i), versions(i), healthChecks(i))

      def stopTask(instance: Instance) =
        taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(instance.instanceId)).futureValue

      // one other task of another app
      val otherAppId = "other".toRootPath
      val otherInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(version = Some(Timestamp.zero)).getInstance()
      val otherHealthChecks = Set[HealthCheck](MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true")))
      val otherApp = startTask(otherAppId, otherInstance, Timestamp(42), otherHealthChecks)

      hcManager.addAllFor(otherApp, Seq.empty)
      assert(hcManager.list(otherAppId) == otherHealthChecks) // linter:ignore:UnlikelyEquality

      // start task 0 without running health check
      var currentAppVersion = startTask_i(0)
      assert(hcManager.list(appId) == Set.empty[HealthCheck])
      groupManager.appVersion(currentAppVersion.id, currentAppVersion.version.toOffsetDateTime) returns Future.successful(Some(currentAppVersion))

      // reconcile doesn't do anything b/c task 0 has no health checks
      hcManager.reconcile(Seq(currentAppVersion))
      assert(hcManager.list(appId) == Set.empty[HealthCheck])

      // reconcile starts health checks of task 1
      val captured1 = captureEvents.forBlock {
        assert(hcManager.list(appId) == Set.empty[HealthCheck])
        currentAppVersion = startTask_i(1)
        groupManager.appVersion(currentAppVersion.id, currentAppVersion.version.toOffsetDateTime) returns Future.successful(Some(currentAppVersion))
        hcManager.reconcile(Seq(currentAppVersion)).futureValue
      }
      assert(captured1.map(_.eventType) == Vector("add_health_check_event"))
      assert(hcManager.list(appId) == healthChecks(1)) // linter:ignore:UnlikelyEquality

      // reconcile leaves health check running
      val captured2 = captureEvents.forBlock {
        hcManager.reconcile(Seq(currentAppVersion)).futureValue
      }
      assert(captured2.isEmpty)
      assert(hcManager.list(appId) == healthChecks(1)) // linter:ignore:UnlikelyEquality

      // reconcile starts health checks of task 2 and leaves those of task 1 running
      val captured3 = captureEvents.forBlock {
        currentAppVersion = startTask_i(2)
        groupManager.appVersion(currentAppVersion.id, currentAppVersion.version.toOffsetDateTime) returns Future.successful(Some(currentAppVersion))
        hcManager.reconcile(Seq(currentAppVersion)).futureValue
      }
      assert(captured3.map(_.eventType) == Vector("add_health_check_event", "add_health_check_event"))
      assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2)) // linter:ignore:UnlikelyEquality

      // reconcile stops health checks which are not current and which are without tasks
      val captured4 = captureEvents.forBlock {
        stopTask(instances(1))
        assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2)) // linter:ignore:UnlikelyEquality
        hcManager.reconcile(Seq(currentAppVersion)).futureValue //wrong
      }
      assert(captured4.map(_.eventType) == Vector("remove_health_check_event"))
      assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality

      // reconcile leaves current version health checks running after termination
      val captured5 = captureEvents.forBlock {
        stopTask(instances(2))
        assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality
        hcManager.reconcile(Seq(currentAppVersion)).futureValue //wrong
      }
      assert(captured5.map(_.eventType) == Vector.empty)
      assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality

      // other task was not touched
      assert(hcManager.list(otherAppId) == otherHealthChecks) // linter:ignore:UnlikelyEquality
    }

    "reconcile loads the last known task health state" in new Fixture {
      val healthCheck = MesosCommandHealthCheck(command = Command("true"))
      val app: AppDefinition = AppDefinition(id = appId, healthChecks = Set(healthCheck))

      // Create a task
      val instance: Instance = TestInstanceBuilder.newBuilder(appId, version = app.version).addTaskStaged().getInstance()
      val instanceId = instance.instanceId
      val (taskId, _) = instance.tasksMap.head
      taskCreationHandler.created(InstanceUpdateOperation.LaunchEphemeral(instance)).futureValue

      // Send an unhealthy update
      val taskStatus = TestTaskBuilder.Helper.unhealthyTask(taskId).status.mesosStatus.get
      val update = InstanceUpdateOperation.MesosUpdate(instance, taskStatus, clock.now())
      stateOpProcessor.process(update).futureValue

      assert(hcManager.status(app.id, instanceId).futureValue.isEmpty)

      groupManager.appVersion(app.id, app.version.toOffsetDateTime) returns Future.successful(Some(app))
      // Reconcile health checks
      hcManager.reconcile(Seq(app)).futureValue
      val health = hcManager.status(app.id, instanceId).futureValue.head

      assert(health.lastFailure.isDefined)
      assert(health.lastSuccess.isEmpty)
    }
  }
  def captureEvents(implicit eventStream: EventStream) = new CaptureEvents(eventStream)
}
