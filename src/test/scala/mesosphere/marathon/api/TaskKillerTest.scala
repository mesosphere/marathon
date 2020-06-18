package mesosphere.marathon
package api

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.{InstanceUpdateOperation, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.state._
import mesosphere.AkkaUnitTest
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._

import scala.concurrent.Future

class TaskKillerTest extends AkkaUnitTest {
  "TaskKiller" should {
    //regression for #3251
    "No tasks to kill should return with an empty array" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/invalid")
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId, role = "*")))

      val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Instance]).futureValue
      result.isEmpty shouldEqual true
    }

    "AppNotFound" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/invalid")
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))
      when(f.groupManager.runSpec(appId)).thenReturn(None)

      val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Instance])
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "AppNotFound with scaling" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/invalid")
      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.empty))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(Seq.empty))

      val result = f.taskKiller.killAndScale(appId, (tasks) => Seq.empty[Instance], force = true)
      result.failed.futureValue shouldEqual PathNotFoundException(appId)
    }

    "KillRequested with scaling" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/app")
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance1, instance2)

      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.forInstances(tasksToKill)))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))
      when(f.groupManager.group(appId.parent)).thenReturn(Some(Group.empty(appId.parent)))

      val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(RootGroup) => RootGroup])
      val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
      val toKillCaptor = ArgumentCaptor.forClass(classOf[Map[AbsolutePathId, Seq[Instance]]])
      val expectedDeploymentPlan = DeploymentPlan.empty
      when(
        f.groupManager
          .updateRoot(any[AbsolutePathId], groupUpdateCaptor.capture(), any[Timestamp], forceCaptor.capture(), toKillCaptor.capture())
      ).thenReturn(Future.successful(expectedDeploymentPlan))

      val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = true)
      result.futureValue shouldEqual expectedDeploymentPlan
      forceCaptor.getValue shouldEqual true
      toKillCaptor.getValue shouldEqual Map(appId -> tasksToKill)
    }

    "KillRequested without scaling" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/my/app")
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance)
      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId, role = "*")))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))

      val result = f.taskKiller.kill(
        appId,
        { tasks =>
          tasks should equal(tasksToKill)
          tasksToKill
        }
      )

      result.futureValue shouldEqual tasksToKill
      verify(f.killService, times(1)).killInstancesAndForget(tasksToKill, KillReason.KillingTasksViaApi)
    }

    "Kill and scale w/o force should fail if there is a deployment" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/my/app")
      val instance1 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val tasksToKill = Seq(instance1, instance2)

      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(tasksToKill))
      when(f.tracker.instancesBySpec()).thenReturn(Future.successful(InstancesBySpec.forInstances(tasksToKill)))
      when(f.groupManager.group(appId.parent)).thenReturn(Some(Group.empty(appId.parent)))
      val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(RootGroup) => RootGroup])
      val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
      when(
        f.groupManager.updateRoot(
          any[AbsolutePathId],
          groupUpdateCaptor.capture(),
          any[Timestamp],
          forceCaptor.capture(),
          any[Map[AbsolutePathId, Seq[Instance]]]
        )
      ).thenReturn(Future.failed(AppLockedException()))

      val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = false)
      result.failed.futureValue shouldEqual AppLockedException()
      forceCaptor.getValue shouldEqual false
    }

    "kill with wipe will kill running and expunge all" in {
      val f = new Fixture
      import f.auth.identity
      val appId = AbsolutePathId("/my/app")
      val app = AppDefinition(appId, role = "*")
      val runningInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
      val reservedInstance: Instance = TestInstanceBuilder.scheduledWithReservation(app)
      val instancesToKill = Seq(runningInstance, reservedInstance)

      when(f.groupManager.runSpec(appId)).thenReturn(Some(AppDefinition(appId, role = "*")))
      when(f.tracker.specInstances(appId)).thenReturn(Future.successful(instancesToKill))
      when(f.tracker.forceExpunge(runningInstance.instanceId)).thenReturn(Future.successful(Done))
      when(f.tracker.forceExpunge(reservedInstance.instanceId)).thenReturn(Future.successful(Done))

      val result = f.taskKiller.kill(
        appId,
        { instances =>
          instances should equal(instancesToKill)
          instancesToKill
        },
        wipe = true
      )
      result.futureValue shouldEqual instancesToKill
      // all found instances are expunged and the launched instance is eventually expunged again
      verify(f.tracker, atLeastOnce).forceExpunge(runningInstance.instanceId)
      verify(f.tracker).forceExpunge(reservedInstance.instanceId)
    }

    "allows kill and scale for an app for which a user specifically has access" in {
      // Regression test for MARATHON-8731
      val business = Builders.newAppDefinition.command(id = AbsolutePathId("/business"))
      val devBackend = Builders.newAppDefinition.command(id = AbsolutePathId("/dev/backend"))
      val initialRoot = Builders.newRootGroup(apps = Seq(business, devBackend))
      val businessInstance = TestInstanceBuilder.newBuilderForRunSpec(business).addTaskRunning().instance
      val devBackendInstance = TestInstanceBuilder.newBuilderForRunSpec(devBackend).addTaskRunning().instance
      val authFn: Any => Boolean = {
        case app: AppDefinition =>
          (app.id == devBackend.id)
        case _ =>
          ???
      }
      new FixtureWithRealInstanceTracker(initialRoot, authFn) {
        instanceTracker.process(InstanceUpdateOperation.Schedule(businessInstance)).futureValue
        instanceTracker.process(InstanceUpdateOperation.Schedule(devBackendInstance)).futureValue

        val deployment = taskKiller.killAndScale(Map(devBackendInstance.runSpecId -> Seq(devBackendInstance)), force = false).futureValue
        deployment.affectedRunSpecIds shouldBe Set(devBackend.id)
      }
    }
  }

  class FixtureWithRealInstanceTracker(initialRoot: RootGroup = RootGroup.empty(), authFn: Any => Boolean = _ => true) {
    val testInstanceTrackerFixture = new TestInstanceTrackerFixture(initialRoot, authFn = authFn)
    val instanceTracker = testInstanceTrackerFixture.instanceTracker
    val killService: KillService = mock[KillService]
    val auth = testInstanceTrackerFixture.authFixture.auth
    implicit val identity: Identity = testInstanceTrackerFixture.authFixture.identity

    testInstanceTrackerFixture.service.deploy(any, any).returns(Future(Done))
    val taskKiller: TaskKiller = new TaskKiller(
      instanceTracker,
      testInstanceTrackerFixture.groupManager,
      testInstanceTrackerFixture.authFixture.auth,
      testInstanceTrackerFixture.authFixture.auth,
      killService
    )
  }

  class Fixture {
    val auth: TestAuthFixture = new TestAuthFixture
    val tracker: InstanceTracker = mock[InstanceTracker]
    tracker.setGoal(any, any, any).returns(Future.successful(Done))
    tracker.instanceUpdates.returns(Source.single(InstancesSnapshot(Nil) -> Source.empty))
    val killService: KillService = mock[KillService]
    val groupManager: GroupManager = mock[GroupManager]

    implicit val system = ActorSystem("test")

    def materializerSettings = ActorMaterializerSettings(system)

    implicit val mat = ActorMaterializer(materializerSettings)
    val taskKiller: TaskKiller = new TaskKiller(tracker, groupManager, auth.auth, auth.auth, killService)
  }
}
