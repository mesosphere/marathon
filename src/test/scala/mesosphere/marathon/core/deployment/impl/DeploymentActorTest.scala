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
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.KillReason
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Matchers
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
class DeploymentActorTest extends AkkaUnitTest with GroupCreation with Eventually {

  implicit val defaultTimeout: Timeout = 5.seconds

  class Fixture {
    val scheduler: scheduling.Scheduler = mock[scheduling.Scheduler]
    scheduler.stop(any[Seq[Instance]], any)(any) returns Future.successful(Done)
    scheduler.decommission(any[Seq[Instance]], any)(any) returns Future.successful(Done)
    scheduler.decommission(any[Instance], any)(any) returns Future.successful(Done)

    val schedulerActions: SchedulerActions = mock[SchedulerActions]
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

    def instanceKilled(instance: Instance): InstanceChanged = {
      val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed))
      InstanceChanged(instance.instanceId, instance.runSpecVersion, instance.runSpecId, Condition.Killed, instance)
    }

    def deploymentActor(manager: ActorRef, plan: DeploymentPlan) = system.actorOf(
      DeploymentActor.props(
        manager,
        schedulerActions,
        scheduler,
        plan,
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
      val instance1_1 = {
        val instance = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance1_2 = {
        val instance = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance2_1 = {
        val instance = TestInstanceBuilder.newBuilder(app2.id, version = app2.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance3_1 = {
        val instance = TestInstanceBuilder.newBuilder(app3.id, version = app3.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }
      val instance4_1 = {
        val instance = TestInstanceBuilder.newBuilder(app4.id, version = app4.version).addTaskRunning().getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }

      val plan = DeploymentPlan(origGroup, targetGroup)

      //queue.purge(any) returns Future.successful(Done)
      schedulerActions.startRunSpec(any) returns Future.successful(Done)
      scheduler.getInstances(Matchers.eq(app1.id))(any[ExecutionContext]) returns Future.successful(Seq(instance1_1, instance1_2))
      scheduler.getInstances(Matchers.eq(app2.id))(any) returns Future.successful(Seq(instance2_1))
      scheduler.getInstances(Matchers.eq(app3.id))(any) returns Future.successful(Seq(instance3_1))
      scheduler.getInstances(Matchers.eq(app4.id))(any) returns Future.successful(Seq(instance4_1))
      scheduler.getInstance(Matchers.eq(instance1_1.instanceId))(any) returns Future.successful(Some(instance1_1))
      scheduler.getInstance(Matchers.eq(instance1_2.instanceId))(any) returns Future.successful(Some(instance1_2))
      scheduler.getInstance(Matchers.eq(instance2_1.instanceId))(any) returns Future.successful(Some(instance2_1))
      scheduler.getInstance(Matchers.eq(instance3_1.instanceId))(any) returns Future.successful(Some(instance3_1))
      scheduler.getInstance(Matchers.eq(instance4_1.instanceId))(any) returns Future.successful(Some(instance4_1))

      scheduler.sync(Matchers.eq(app2New))(any) returns Future.successful(Done)
      scheduler.reschedule(any[Seq[Instance]], same(app2New))(any) returns Future.successful(Done)
      when(scheduler.schedule(same(app2New), any[Int])(any)).thenAnswer(new Answer[Future[Done]] {
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

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance1_2), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance1_2))

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance2_1), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance2_1))

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance4_1), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance4_1))

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      verify(scheduler).resetDelay(app4.copy(instances = 0))
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

      scheduler.getInstances(Matchers.eq(app.id))(any) returns Future.successful(Seq(instance1_1, instance1_2))
      scheduler.getInstance(Matchers.eq(instance1_1.instanceId))(any) returns Future.successful(Some(instance1_1))
      scheduler.getInstance(Matchers.eq(instance1_2.instanceId))(any) returns Future.successful(Some(instance1_2))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      scheduler.sync(Matchers.eq(appNew))(any) returns Future.successful(Done)
      scheduler.reschedule(any[Seq[Instance]], same(appNew))(any) returns Future.successful(Done)
      when(scheduler.schedule(same(appNew), any[Int])(any)).thenAnswer(new Answer[Future[Done]] {
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

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance1_1), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance1_1))

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance1_2), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance1_2))

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))

      verify(scheduler).schedule(Matchers.eq(appNew), Matchers.eq(2))(any)
    }

    "Restart suspended app" in new Fixture {
      val managerProbe = TestProbe()

      val app = AppDefinition(id = PathId("/foo/app1"), cmd = Some("cmd"), instances = 0)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo"), Map(appNew.id -> appNew))))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

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

      scheduler.getInstances(Matchers.eq(app1.id))(any) returns Future.successful(Seq(instance1_1, instance1_2, instance1_3))

      deploymentActor(managerProbe.ref, plan)

      eventually {
        verify(scheduler).decommission(Matchers.eq(instance1_2), any[KillReason])(any)
      }
      system.eventStream.publish(instanceKilled(instance1_2))

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan, Success(Done)))
    }
  }
}
