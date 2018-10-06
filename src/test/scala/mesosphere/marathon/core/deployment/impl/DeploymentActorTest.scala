package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.ActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment._
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.DeploymentFinished
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.task.tracker.InstanceTrackerFixture
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
class DeploymentActorTest extends AkkaUnitTest with GroupCreation {

  implicit val defaultTimeout: Timeout = 5.seconds

  class Fixture extends InstanceTrackerFixture {
    override val actorSystem = system
    val queue: LaunchQueue = mock[LaunchQueue]
    val killService = new KillServiceMock(system)
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val config: DeploymentConfig = mock[DeploymentConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    config.killBatchSize returns 100
    config.killBatchCycle returns 10.seconds

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      instance.instanceId returns instanceId
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def deploymentActor(manager: ActorRef, plan: DeploymentPlan) = system.actorOf(
      DeploymentActor.props(
        manager,
        killService,
        scheduler,
        plan,
        instanceTracker,
        queue,
        hcManager,
        system.eventStream,
        readinessCheckExecutor
      )
    )

  }

  "DeploymentActor" should {
    "Deploy" in new Fixture {
      val managerProbe = TestProbe()
      val app1 = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 2)
      val app2 = AppDefinition(id = PathId("/foo/app2"), cmd = Some("cmd"), instances = 1)
      val app3 = AppDefinition(id = PathId("/foo/app3"), cmd = Some("cmd"), instances = 1)
      val app4 = AppDefinition(id = PathId("/foo/app4"), cmd = Some("cmd"))
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(
        app1.id -> app1,
        app2.id -> app2,
        app4.id -> app4))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 1, versionInfo = version2)
      val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(
        app1New.id -> app1New,
        app2New.id -> app2New,
        app3.id -> app3))))

      // setting started at to 0 to make sure this survives
      val instance1_1 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()
      val instance2_1 = TestInstanceBuilder.newBuilder(app2.id, version = app2.version).addTaskRunning().getInstance()
      val instance3_1 = TestInstanceBuilder.newBuilder(app3.id, version = app3.version).addTaskRunning().getInstance()
      val instance4_1 = TestInstanceBuilder.newBuilder(app4.id, version = app4.version).addTaskRunning().getInstance()

      val plan = DeploymentPlan(origGroup, targetGroup)

      queue.purge(any) returns Future.successful(Done)
      scheduler.startRunSpec(any) returns Future.successful(Done)
      populateInstances(Seq(instance1_1, instance1_2, instance2_1, instance3_1, instance4_1))

      queue.sync(app2New) returns Future.successful(Done)
      when(queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(app2New, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(7.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      goalChangeMap should contain allOf (
        instance1_2.instanceId -> Goal.Decommissioned, // killed due to scale down
        instance2_1.instanceId -> Goal.Decommissioned, // killed due to config change
        instance4_1.instanceId -> Goal.Decommissioned // killed because app4 does not exist anymore
      )
      goalChangeMap should have size 3
      verify(queue).resetDelay(app4.copy(instances = 0))
    }

    "Restart app" in new Fixture {
      val managerProbe = TestProbe()
      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 2)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      populateInstances(Seq(instance1_1, instance1_2))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      queue.sync(appNew) returns Future.successful(Done)
      when(queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(appNew, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }
      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      goalChangeMap should contain allOf (
        instance1_1.instanceId -> Goal.Decommissioned,
        instance1_2.instanceId -> Goal.Decommissioned
      )
      verify(queue).add(appNew, 2)
    }

    "Restart suspended app" in new Fixture {
      val managerProbe = TestProbe()

      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 0)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      instanceTracker.specInstancesSync(app.id) returns Seq.empty[Instance]
      queue.add(app, 2) returns Future.successful(Done)

      deploymentActor(managerProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }
      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))
    }

    "Scale with tasksToKill" in new Fixture {
      val managerProbe = TestProbe()
      val app1 = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 3)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app1.id -> app1))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 2, versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app1New.id -> app1New))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(500)).getInstance()
      val instance1_3 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Seq(instance1_2)))

      populateInstances(Seq(instance1_1, instance1_2, instance1_3))

      deploymentActor(managerProbe.ref, plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      goalChangeMap should have size 1
      goalChangeMap should contain (instance1_2.instanceId -> Goal.Decommissioned)
    }
  }
}
