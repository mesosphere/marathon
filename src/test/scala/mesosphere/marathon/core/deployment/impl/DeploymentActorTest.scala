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
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Matchers
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
class DeploymentActorTest extends AkkaUnitTest with GroupCreation {

  implicit val defaultTimeout: Timeout = 5.seconds

  class Fixture {
    val tracker: InstanceTracker = mock[InstanceTracker]
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

    def deploymentActor(manager: ActorRef, promise: Promise[Done], plan: DeploymentPlan) = system.actorOf(
      DeploymentActor.props(
        manager,
        promise,
        killService,
        scheduler,
        plan,
        tracker,
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
      val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
      val app2 = AppDefinition(id = PathId("/app2"), cmd = Some("cmd"), instances = 1)
      val app3 = AppDefinition(id = PathId("/app3"), cmd = Some("cmd"), instances = 1)
      val app4 = AppDefinition(id = PathId("/app4"), cmd = Some("cmd"))
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(
        app1.id -> app1,
        app2.id -> app2,
        app4.id -> app4))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 1, versionInfo = version2)
      val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(
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

      scheduler.startRunSpec(any) returns Future.successful(Done)
      tracker.specInstances(Matchers.eq(app1.id))(any[ExecutionContext]) returns Future.successful(Seq(instance1_1, instance1_2))
      tracker.specInstancesSync(app2.id) returns Seq(instance2_1)
      tracker.specInstances(Matchers.eq(app2.id))(any[ExecutionContext]) returns Future.successful(Seq(instance2_1))
      tracker.specInstances(Matchers.eq(app3.id))(any[ExecutionContext]) returns Future.successful(Seq(instance3_1))
      tracker.specInstances(Matchers.eq(app4.id))(any[ExecutionContext]) returns Future.successful(Seq(instance4_1))

      when(queue.addAsync(same(app2New), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(app2New, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, Promise[Done](), plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(7.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      withClue(killService.killed.mkString(",")) {
        killService.killed should contain(instance1_2.instanceId) // killed due to scale down
        killService.killed should contain(instance2_1.instanceId) // killed due to config change
        killService.killed should contain(instance4_1.instanceId) // killed because app4 does not exist anymore
        killService.numKilled should be(3)
        verify(scheduler).stopRunSpec(app4.copy(instances = 0))
      }
    }

    "Restart app" in new Fixture {
      val managerProbe = TestProbe()
      val promise = Promise[Done]()
      val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(appNew.id -> appNew))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      tracker.specInstancesSync(app.id) returns Seq(instance1_1, instance1_2)
      tracker.specInstances(app.id) returns Future.successful(Seq(instance1_1, instance1_2))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      queue.countAsync(appNew.id) returns Future.successful(appNew.instances)

      when(queue.addAsync(same(appNew), any[Int])).thenAnswer(new Answer[Future[Done]] {
        def answer(invocation: InvocationOnMock): Future[Done] = {
          for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
            system.eventStream.publish(instanceChanged(appNew, Condition.Running))
          Future.successful(Done)
        }
      })

      deploymentActor(managerProbe.ref, promise, plan)
      promise.future.futureValue should be (Done)

      killService.killed should contain(instance1_1.instanceId)
      killService.killed should contain(instance1_2.instanceId)
      verify(queue).addAsync(appNew, 2)
    }

    "Restart suspended app" in new Fixture {
      val managerProbe = TestProbe()
      val promise = Promise[Done]()

      val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 0)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app.id -> app))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(appNew.id -> appNew))))

      val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

      tracker.specInstancesSync(app.id) returns Seq.empty[Instance]
      queue.addAsync(app, 2) returns Future.successful(Done)

      deploymentActor(managerProbe.ref, promise, plan)
      promise.future.futureValue should be (Done)
    }

    "Scale with tasksToKill" in new Fixture {
      val managerProbe = TestProbe()
      val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 3)
      val origGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app1.id -> app1))))

      val version2 = VersionInfo.forNewConfig(Timestamp(1000))
      val app1New = app1.copy(instances = 2, versionInfo = version2)

      val targetGroup = createRootGroup(groups = Set(createGroup(PathId("/foo/bar"), Map(app1New.id -> app1New))))

      val instance1_1 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
      val instance1_2 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(500)).getInstance()
      val instance1_3 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

      val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Seq(instance1_2)))

      tracker.specInstances(Matchers.eq(app1.id))(any[ExecutionContext]) returns Future.successful(Seq(instance1_1, instance1_2, instance1_3))

      deploymentActor(managerProbe.ref, Promise[Done](), plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      killService.numKilled should be(1)
      killService.killed should contain(instance1_2.instanceId)
    }
  }
}
