package mesosphere.marathon
package core.health.impl

import akka.event.EventStream
import com.typesafe.config.{Config, ConfigFactory}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{Health, HealthCheck, MesosCommandHealthCheck}
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.instance.{Instance, TestTaskBuilder}
import mesosphere.marathon.core.leadership.{AlwaysElectedLeadershipModule, LeadershipModule}
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerModule}
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state._
import mesosphere.marathon.test.{CaptureEvents, MarathonTestHelper, SettableClock}
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.{Protos => mesos}
import org.scalatest.concurrent.Eventually

import scala.async.Async.{async, await}
import scala.collection.immutable.Set
import scala.collection.mutable.ListBuffer
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
    val instanceTrackerModule: InstanceTrackerModule = MarathonTestHelper.createTaskTrackerModule(leadershipModule)
    implicit val instanceTracker: InstanceTracker = instanceTrackerModule.instanceTracker
    val groupManager: GroupManager = mock[GroupManager]
    implicit val eventStream: EventStream = new EventStream(system)
    val killService: KillService = mock[KillService]
    implicit val hcManager: MarathonHealthCheckManager = new MarathonHealthCheckManager(
      system,
      killService,
      eventStream,
      instanceTracker,
      groupManager
    )
  }

  def setupTrackerWithProvisionedInstance(appId: PathId, version: Timestamp, instanceTracker: InstanceTracker): Future[Instance] = async {
    val app = AppDefinition(appId, versionInfo = VersionInfo.OnlyVersion(version))
    val scheduledInstance = Instance.scheduled(app)
    // schedule
    await(instanceTracker.schedule(scheduledInstance))
    // provision
    val now = Timestamp.now()
    val provisionedTasks = Tasks.provisioned(Task.Id(scheduledInstance.instanceId), NetworkInfoPlaceholder(), version, now)
    val updateOperation = InstanceUpdateOperation.Provision(scheduledInstance.instanceId, AgentInfoPlaceholder(), app, provisionedTasks, now)
    val updateEffect = await(instanceTracker.process(updateOperation)).asInstanceOf[InstanceUpdateEffect.Update]

    updateEffect.instance
  }

  def setupTrackerWithRunningInstance(appId: PathId, version: Timestamp, instanceTracker: InstanceTracker): Future[Instance] = async {
    val instance: Instance = await(setupTrackerWithProvisionedInstance(appId, version, instanceTracker))
    val (taskId, _) = instance.tasksMap.head
    // update to running
    val taskStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(true)
      .build
    await(instanceTracker.updateStatus(instance, taskStatus, Timestamp.now()))
    await(instanceTracker.get(instance.instanceId).map(_.get))
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

      val healthCheck = MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true"))

      val instance = setupTrackerWithRunningInstance(appId, Timestamp.zero, instanceTracker).futureValue
      val (instanceId, taskId) = (instance.instanceId, instance.tasksMap.head._2)
      val taskStatus = TestTaskBuilder.Helper.unhealthyTask(instanceId).status.mesosStatus.get

      instanceTracker.updateStatus(instance, taskStatus, clock.now()).futureValue

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

      val instance1 = setupTrackerWithRunningInstance(appId, version, instanceTracker).futureValue
      val (instanceId1, taskId1) = (instance1.instanceId, instance1.tasksMap.head._2.taskId)
      val instance2 = setupTrackerWithRunningInstance(appId, version, instanceTracker).futureValue
      val (instanceId2, taskId2) = (instance2.instanceId, instance2.tasksMap.head._2.taskId)
      val instance3 = setupTrackerWithRunningInstance(appId, version, instanceTracker).futureValue
      val (instanceId3, taskId3) = (instance3.instanceId, instance3.tasksMap.head._2.taskId)

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
      var instances = new ListBuffer[Instance]()
      var currentApp: AppDefinition = _

      def startInstance(appId: PathId, version: Timestamp, healthChecks: Set[HealthCheck]): (Instance, AppDefinition) = {
        val app = AppDefinition(
          id = appId,
          versionInfo = VersionInfo.forNewConfig(version),
          healthChecks = healthChecks
        )
        val instance = setupTrackerWithRunningInstance(appId, version, instanceTracker).futureValue
        (instance, app)
      }

      def startInstance_i(i: Int): (Instance, AppDefinition) = startInstance(appId, versions(i), healthChecks(i))

      def stopTask(instance: Instance) =
        instanceTracker.forceExpunge(instance.instanceId).futureValue

      // one other task of another app
      val otherAppId = "other".toRootPath
      val otherHealthChecks = Set[HealthCheck](MesosCommandHealthCheck(gracePeriod = 0.seconds, command = Command("true")))
      val (otherInstance, otherApp) = startInstance(otherAppId, Timestamp(42), otherHealthChecks)

      hcManager.addAllFor(otherApp, Seq.empty)
      assert(hcManager.list(otherAppId) == otherHealthChecks) // linter:ignore:UnlikelyEquality

      // start task 0 without running health check
      val (instance0, app0) = startInstance_i(0)
      instances += instance0
      currentApp = app0
      assert(hcManager.list(appId) == Set.empty[HealthCheck])
      groupManager.appVersion(app0.id, app0.version.toOffsetDateTime) returns Future.successful(Some(app0))

      // reconcile doesn't do anything b/c task 0 has no health checks
      hcManager.reconcile(Seq(app0))
      assert(hcManager.list(appId) == Set.empty[HealthCheck])

      // reconcile starts health checks of task 1
      val captured1 = captureEvents.forBlock {
        val (instance1, app1) = startInstance_i(1)
        currentApp = app1
        instances += instance1
        assert(hcManager.list(appId) == Set.empty[HealthCheck])
        groupManager.appVersion(app1.id, app1.version.toOffsetDateTime) returns Future.successful(Some(app1))
        hcManager.reconcile(Seq(app1)).futureValue
      }
      assert(captured1.map(_.eventType).count(_ == "add_health_check_event") == 1)
      assert(hcManager.list(appId) == healthChecks(1)) // linter:ignore:UnlikelyEquality

      // reconcile leaves health check running
      val captured2 = captureEvents.forBlock {
        hcManager.reconcile(Seq(currentApp)).futureValue
      }
      assert(captured2.isEmpty)
      assert(hcManager.list(appId) == healthChecks(1)) // linter:ignore:UnlikelyEquality

      // reconcile starts health checks of task 2 and leaves those of task 1 running
      val captured3 = captureEvents.forBlock {
        val (instance2, app2) = startInstance_i(2)
        currentApp = app2
        instances += instance2
        groupManager.appVersion(app2.id, app2.version.toOffsetDateTime) returns Future.successful(Some(app2))
        hcManager.reconcile(Seq(app2)).futureValue
      }
      assert(captured3.map(_.eventType).count(_ == "add_health_check_event") == 2)
      assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2)) // linter:ignore:UnlikelyEquality

      // reconcile stops health checks which are not current and which are without tasks
      val captured4 = captureEvents.forBlock {
        stopTask(instances(1))
        assert(hcManager.list(appId) == healthChecks(1) ++ healthChecks(2)) // linter:ignore:UnlikelyEquality
        hcManager.reconcile(Seq(currentApp)).futureValue //wrong
      }
      assert(captured4.map(_.eventType) == Vector("remove_health_check_event"))
      assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality

      // reconcile leaves current version health checks running after termination
      val captured5 = captureEvents.forBlock {
        stopTask(instances(2))
        assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality
        hcManager.reconcile(Seq(currentApp)).futureValue //wrong
      }
      assert(captured5.map(_.eventType) == Vector.empty)
      assert(hcManager.list(appId) == healthChecks(2)) // linter:ignore:UnlikelyEquality

      // other task was not touched
      assert(hcManager.list(otherAppId) == otherHealthChecks) // linter:ignore:UnlikelyEquality
    }

    "reconcile loads the last known task health state" in new Fixture {
      val healthCheck = MesosCommandHealthCheck(command = Command("true"))
      val app: AppDefinition = AppDefinition(id = appId, healthChecks = Set(healthCheck))

      // Send an unhealthy update
      val instance = setupTrackerWithRunningInstance(appId, app.version, instanceTracker).futureValue
      val taskStatus = TestTaskBuilder.Helper.unhealthyTask(instance.instanceId).status.mesosStatus.get
      instanceTracker.updateStatus(instance, taskStatus, clock.now()).futureValue

      assert(hcManager.status(app.id, instance.instanceId).futureValue.isEmpty)

      groupManager.appVersion(app.id, app.version.toOffsetDateTime) returns Future.successful(Some(app))
      // Reconcile health checks
      hcManager.reconcile(Seq(app)).futureValue
      val health = hcManager.status(app.id, instance.instanceId).futureValue.head

      health.lastFailure.isDefined should be (true) withClue (s"Expecting health lastFailure to be defined, but it was None: $health")
      health.lastSuccess.isEmpty should be (true) withClue (s"Expecting health lastSuccess to be empty, but it was '${health.lastSuccess}': $health")
    }
  }
  def captureEvents(implicit eventStream: EventStream) = new CaptureEvents(eventStream)
}
