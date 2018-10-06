package mesosphere.marathon
package api

import akka.Done
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTrackerFixture
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state._
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

class TaskKillerTest extends AkkaUnitTest with Eventually {

  val auth: TestAuthFixture = new TestAuthFixture
  implicit val identity = auth.identity

  "TaskKiller" should {
    //regression for #3251
    "No tasks to kill should return with an empty array" in {
      val f = new Fixture
      val appId = PathId("invalid")
      when(f.instanceTracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))

      val result = f.taskKiller.kill(appId, (_) => Seq.empty[Instance]).futureValue
      result.isEmpty shouldEqual true
    }

    "AppNotFound" in {
      val f = new Fixture
      val appId = PathId("invalid")
      when(f.instanceTracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(None)

      val result = f.taskKiller.kill(appId, (_) => Seq.empty[Instance])
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "AppNotFound with scaling" in {
      val f = new Fixture
      val appId = PathId("invalid")

      when(f.instanceTracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.empty))
      when(f.instanceTracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))

      val result = f.taskKiller.killAndScale(appId, (tasks) => Seq.empty[Instance], force = true)
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "KillRequested with scaling" in {
      val f = new Fixture
      val appId = PathId(List("app"))
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instancesToKill = Seq(instance1, instance2)
      f.populateInstances(instancesToKill)

      when(f.groupManager.group(appId.parent)).thenReturn(Some(Group.empty(appId.parent)))

      val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(RootGroup) => RootGroup])
      val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
      val toKillCaptor = ArgumentCaptor.forClass(classOf[Map[PathId, Seq[Instance]]])
      val expectedDeploymentPlan = DeploymentPlan.empty
      when(f.groupManager.updateRoot(
        any[PathId],
        groupUpdateCaptor.capture(),
        any[Timestamp],
        forceCaptor.capture(),
        toKillCaptor.capture())
      ).thenReturn(Future.successful(expectedDeploymentPlan))

      val result = f.taskKiller.killAndScale(appId, (tasks) => instancesToKill, force = true)
      result.futureValue shouldEqual expectedDeploymentPlan
      forceCaptor.getValue shouldEqual true
      toKillCaptor.getValue shouldEqual Map(appId -> instancesToKill)
    }

    "KillRequested without scaling" in {
      val f = new Fixture
      val appId = PathId(List("my", "app"))
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instancesToKill = Seq(instance)
      f.populateInstances(instancesToKill)
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))
      when(f.killService.killInstances(any, any)).thenReturn(Future.successful(Done))

      val result = f.taskKiller.kill(appId, { tasks =>
        tasks should equal(instancesToKill)
        instancesToKill
      })

      result.futureValue shouldEqual instancesToKill
      verify(f.killService).killInstances(instancesToKill, KillReason.KillingTasksViaApi)
    }

    "Kill and scale w/o force should fail if there is a deployment" in {
      val f = new Fixture
      val appId = PathId(List("my", "app"))
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instancesToKill = Seq(instance1, instance2)
      f.populateInstances(instancesToKill)

      when(f.groupManager.group(appId.parent)).thenReturn(Some(Group.empty(appId.parent)))
      val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(RootGroup) => RootGroup])
      val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
      when(f.groupManager.updateRoot(
        any[PathId],
        groupUpdateCaptor.capture(),
        any[Timestamp],
        forceCaptor.capture(),
        any[Map[PathId, Seq[Instance]]]
      )).thenReturn(Future.failed(AppLockedException()))

      val result = f.taskKiller.killAndScale(appId, (_) => instancesToKill, force = false)
      result.failed.futureValue shouldEqual AppLockedException()
      forceCaptor.getValue shouldEqual false
    }

    "kill with wipe will kill running and expunge all" in {
      val f = new Fixture
      val appId = PathId(List("my", "app"))
      val app = AppDefinition(appId)
      val runningInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val reservedInstance: Instance = TestInstanceBuilder.scheduledWithReservation(app)
      val instancesToKill = Seq(runningInstance, reservedInstance)
      f.populateInstances(instancesToKill)

      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))
      when(f.instanceTracker.forceExpunge(runningInstance.instanceId)).thenReturn(Future.successful(Done))
      when(f.instanceTracker.forceExpunge(reservedInstance.instanceId)).thenReturn(Future.successful(Done))
      when(f.killService.watchForKilledInstances(any)).thenReturn(Future.successful(Done))

      val result = f.taskKiller.kill(appId, { instances =>
        instances should equal(instancesToKill)
        instancesToKill
      }, wipe = true)
      result.futureValue shouldEqual instancesToKill
      // only task1 is killed
      f.goalChangeMap should have size 2
      f.goalChangeMap should contain allOf (
        runningInstance.instanceId -> Goal.Decommissioned,
        // TODO: we likely need to verify reservedInstance is set to goal: Stopped
        reservedInstance.instanceId -> Goal.Decommissioned
      )

      // all found instances are expunged and the launched instance is eventually expunged again
      verify(f.instanceTracker, atLeastOnce).forceExpunge(runningInstance.instanceId)
      verify(f.instanceTracker).forceExpunge(reservedInstance.instanceId)
    }
  }

  class Fixture extends InstanceTrackerFixture {
    override val actorSystem = system
    val service: MarathonSchedulerService = mock[MarathonSchedulerService]
    val killService: KillService = mock[KillService]
    val groupManager: GroupManager = mock[GroupManager]

    val config: MarathonConf = mock[MarathonConf]
    when(config.zkTimeoutDuration).thenReturn(1.second)

    val taskKiller: TaskKiller = new TaskKiller(
      instanceTracker, groupManager, service, config, auth.auth, auth.auth, killService)
  }

}
