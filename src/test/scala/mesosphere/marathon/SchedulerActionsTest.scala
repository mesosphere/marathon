package mesosphere.marathon

import akka.Done
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{AppDefinition, PathId, RootGroup, Timestamp}
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verifyNoMoreInteractions
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class SchedulerActionsTest extends AkkaUnitTest {
  "SchedulerActions" should {

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
      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instances))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

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

      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.empty)
      f.groupRepo.root() returns Future.successful(RootGroup())

      f.scheduler.reconcileTasks(f.driver).futureValue

      verify(f.driver, times(1)).reconcileTasks(java.util.Arrays.asList())
    }

    "Kill orphaned task" in {
      val f = new Fixture
      val app = AppDefinition(id = PathId("/myapp"))
      val orphanedApp = AppDefinition(id = PathId("/orphan"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val orphanedInstance = TestInstanceBuilder.newBuilder(orphanedApp.id).addTaskRunning().getInstance()

      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instance, orphanedInstance))
      val rootGroup: RootGroup = RootGroup(apps = Map((app.id, app)))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

      verify(f.instanceTracker, withinTimeout()).setGoal(orphanedInstance.instanceId, Goal.Decommissioned, GoalChangeReason.Orphaned)
    }

    import scala.language.implicitConversions
    implicit def durationToPatienceConfigTimeout(d: FiniteDuration): PatienceConfiguration.Timeout = {
      PatienceConfiguration.Timeout(Span(d.toMillis, Millis))
    }

    class Fixture {
      val queue = mock[LaunchQueue]
      val groupRepo = mock[GroupRepository]
      val instanceTracker = mock[InstanceTracker]
      instanceTracker.setGoal(any, any, any).returns(Future.successful(Done))
      val driver = mock[SchedulerDriver]
      val killService = mock[KillService]
      val clock = new SettableClock()

      queue.add(any, any) returns Future.successful(Done)

      val scheduler = new SchedulerActions(
        groupRepo,
        mock[HealthCheckManager],
        instanceTracker
      )
    }
  }
}
