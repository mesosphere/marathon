package mesosphere.marathon

import akka.Done
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{AppDefinition, PathId, RootGroup}
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.SettableClock
import org.apache.mesos.SchedulerDriver
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{Millis, Span}

import scala.concurrent.Future
import scala.concurrent.duration._

class ReconciliationBehaviorTest extends AkkaUnitTest {
  "ReconciliationBehavior" should {

    "Task reconciliation sends known running and staged tasks and empty list" in {
      val f = new Fixture
      val app = AppDefinition(id = PathId("/myapp"))
      val rootGroup: RootGroup = RootGroup(apps = Map((app.id, app)))
      val runningInstance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val stagedInstance = TestInstanceBuilder.newBuilder(app.id).addTaskStaged().getInstance()

      val stagedInstanceWithSlaveId = TestInstanceBuilder.newBuilder(app.id)
        .addTaskWithBuilder().taskStaged().build()
        .withAgentInfo(agentId = Some("slave 1"))
        .getInstance()

      val instances = Seq(runningInstance, stagedInstance, stagedInstanceWithSlaveId)
      f.mockedTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instances))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.behavior.reconcileTasks(f.driver).futureValue(5.seconds)

      val statuses = Set(
        runningInstance,
        stagedInstance,
        stagedInstanceWithSlaveId
      ).flatMap(_.tasksMap.values).flatMap(_.status.mesosStatus)

      verify(f.driver, withinTimeout()).reconcileTasks(statuses.asJavaCollection)
      verify(f.driver).reconcileTasks(java.util.Arrays.asList())
    }

    "Task reconciliation only one empty list, when no tasks are present in Marathon" in {
      val f = new Fixture

      f.mockedTracker.instancesBySpec() returns Future.successful(InstancesBySpec.empty)
      f.groupRepo.root() returns Future.successful(RootGroup())

      f.behavior.reconcileTasks(f.driver).futureValue

      verify(f.driver, times(1)).reconcileTasks(java.util.Arrays.asList())
    }

    "Kill orphaned task" in {
      val f = new Fixture
      val app = AppDefinition(id = PathId("/myapp"))
      val orphanedApp = AppDefinition(id = PathId("/orphan"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val orphanedInstance = TestInstanceBuilder.newBuilder(orphanedApp.id).addTaskRunning().getInstance()

      f.mockedTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instance, orphanedInstance))
      val rootGroup: RootGroup = RootGroup(apps = Map((app.id, app)))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.behavior.reconcileTasks(f.driver).futureValue(5.seconds)

      verify(f.mockedTracker, withinTimeout()).setGoal(orphanedInstance.instanceId, Goal.Decommissioned, GoalChangeReason.Orphaned)
    }

    import scala.language.implicitConversions
    implicit def durationToPatienceConfigTimeout(d: FiniteDuration): PatienceConfiguration.Timeout = {
      PatienceConfiguration.Timeout(Span(d.toMillis, Millis))
    }

    class Fixture {
      val queue = mock[LaunchQueue]
      val groupRepo = mock[GroupRepository]
      val mockedTracker = mock[InstanceTracker]
      mockedTracker.setGoal(any, any, any).returns(Future.successful(Done))
      val driver = mock[SchedulerDriver]
      val killService = mock[KillService]
      val clock = new SettableClock()

      queue.add(any, any) returns Future.successful(Done)

      val behavior = new ReconciliationBehavior {
        override def groupRepository: GroupRepository = groupRepo
        override def healthCheckManager: HealthCheckManager = mock[HealthCheckManager]
        override def instanceTracker: InstanceTracker = mockedTracker
      }
    }
  }
}
