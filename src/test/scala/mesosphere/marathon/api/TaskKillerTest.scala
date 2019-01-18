package mesosphere.marathon
package api

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import mesosphere.UnitTest
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state._
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class TaskKillerTest extends UnitTest {

  val auth: TestAuthFixture = new TestAuthFixture
  implicit val identity = auth.identity

  "TaskKiller" should {
    //regression for #3251
    "No tasks to kill should return with an empty array" in {
      val f = new Fixture
      val appId = PathId("invalid")
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))

      val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Instance]).futureValue
      result.isEmpty shouldEqual true
    }

    "AppNotFound" in {
      val f = new Fixture
      val appId = PathId("invalid")
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(None)

      val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Instance])
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "AppNotFound with scaling" in {
      val f = new Fixture
      val appId = PathId("invalid")
      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.empty))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))

      val result = f.taskKiller.killAndScale(appId, (tasks) => Seq.empty[Instance], force = true)
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "KillRequested with scaling" in {
      val f = new Fixture
      val appId = PathId(List("app"))
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance1, instance2)

      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.forInstances(tasksToKill: _*)))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))
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

      val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = true)
      result.futureValue shouldEqual expectedDeploymentPlan
      forceCaptor.getValue shouldEqual true
      toKillCaptor.getValue shouldEqual Map(appId -> tasksToKill)
    }

    "KillRequested without scaling" in {
      val f = new Fixture
      val appId = PathId(List("my", "app"))
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance)
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))

      val result = f.taskKiller.kill(appId, { tasks =>
        tasks should equal(tasksToKill)
        tasksToKill
      })

      result.futureValue shouldEqual tasksToKill
      verify(f.killService, times(1)).killInstancesAndForget(tasksToKill, KillReason.KillingTasksViaApi)
    }

    "Kill and scale w/o force should fail if there is a deployment" in {
      val f = new Fixture
      val appId = PathId(List("my", "app"))
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance1, instance2)

      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))
      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.forInstances(tasksToKill: _*)))
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

      val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = false)
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

      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId)))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(instancesToKill))
      when(f.tracker.forceExpunge(runningInstance.instanceId)).thenReturn(Future.successful(Done))
      when(f.tracker.forceExpunge(reservedInstance.instanceId)).thenReturn(Future.successful(Done))

      val result = f.taskKiller.kill(appId, { instances =>
        instances should equal(instancesToKill)
        instancesToKill
      }, wipe = true)
      result.futureValue shouldEqual instancesToKill
      // all found instances are expunged and the launched instance is eventually expunged again
      verify(f.tracker, atLeastOnce).forceExpunge(runningInstance.instanceId)
      verify(f.tracker).forceExpunge(reservedInstance.instanceId)
    }
  }

  class Fixture {
    val tracker: InstanceTracker = mock[InstanceTracker]
    tracker.setGoal(any, any, any).returns(Future.successful(Done))
    tracker.instanceUpdates.returns(Source.single(InstancesSnapshot(Nil) -> Source.empty))
    val killService: KillService = mock[KillService]
    val groupManager: GroupManager = mock[GroupManager]

    val config: MarathonConf = mock[MarathonConf]
    when(config.zkTimeoutDuration).thenReturn(1.second)

    implicit val system = ActorSystem("test")
    def materializerSettings = ActorMaterializerSettings(system)
    implicit val mat = ActorMaterializer(materializerSettings)
    val taskKiller: TaskKiller = new TaskKiller(
      tracker, groupManager, config, auth.auth, auth.auth, killService)
  }

}
