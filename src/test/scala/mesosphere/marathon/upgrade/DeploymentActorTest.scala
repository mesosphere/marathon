package mesosphere.marathon
package upgrade

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFinished, DeploymentStepInfo }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verifyNoMoreInteractions, when }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

// TODO: this is NOT a unit test. the DeploymentActor create child actors that cannot be mocked in the current
// setup which makes the test overly complicated because events etc have to be mocked for these.
// The way forward should be to provide factories that create the child actors with a given context, or
// to use delegates that hide the implementation behind a mockable function call.
class DeploymentActorTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  implicit val defaultTimeout: Timeout = 5.seconds

  test("Deploy") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val app2 = AppDefinition(id = PathId("/app2"), cmd = Some("cmd"), instances = 1)
    val app3 = AppDefinition(id = PathId("/app3"), cmd = Some("cmd"), instances = 1)
    val app4 = AppDefinition(id = PathId("/app4"), cmd = Some("cmd"))
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(
      app1.id -> app1,
      app2.id -> app2,
      app4.id -> app4))))

    val version2 = VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 1, versionInfo = version2)
    val app2New = app2.copy(instances = 2, cmd = Some("otherCmd"), versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(
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

    when(f.tracker.specInstancesLaunchedSync(app1.id)).thenReturn(Seq(instance1_1, instance1_2))
    when(f.tracker.specInstancesLaunchedSync(app2.id)).thenReturn(Seq(instance2_1))
    when(f.tracker.specInstancesLaunchedSync(app3.id)).thenReturn(Seq(instance3_1))
    when(f.tracker.specInstancesLaunchedSync(app4.id)).thenReturn(Seq(instance4_1))

    when(f.queue.add(same(app2New), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        println(invocation.getArguments.toSeq)
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(f.instanceChanged(app2New, Condition.Running))
        true
      }
    })

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      verify(f.scheduler).startRunSpec(app3.copy(instances = 0))
      println(f.killService.killed.mkString(","))
      f.killService.killed should contain (instance1_2.instanceId) // killed due to scale down
      f.killService.killed should contain (instance2_1.instanceId) // killed due to config change
      f.killService.killed should contain (instance4_1.instanceId) // killed because app4 does not exist anymore
      f.killService.numKilled should be (3)
      verify(f.scheduler).stopRunSpec(app4.copy(instances = 0))
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Restart app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 2)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val version2 = VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(appNew.id -> appNew))))

    val instance1_1 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
    val instance1_2 = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Seq(instance1_1, instance1_2))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(f.queue.count(appNew.id)).thenAnswer(new Answer[Int] {
      override def answer(p1: InvocationOnMock): Int = appNew.instances
    })

    when(f.queue.add(same(appNew), any[Int])).thenAnswer(new Answer[Boolean] {
      def answer(invocation: InvocationOnMock): Boolean = {
        for (i <- 0 until invocation.getArguments()(1).asInstanceOf[Int])
          system.eventStream.publish(f.instanceChanged(appNew, Condition.Running))
        true
      }
    })

    try {

      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))

      f.killService.killed should contain (instance1_1.instanceId)
      f.killService.killed should contain (instance1_2.instanceId)
      verify(f.queue).add(appNew, 2)
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Restart suspended app") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()

    val app = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 0)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app.id -> app))))

    val version2 = VersionInfo.forNewConfig(Timestamp(1000))
    val appNew = app.copy(cmd = Some("cmd new"), versionInfo = version2)
    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(appNew.id -> appNew))))

    val plan = DeploymentPlan("foo", origGroup, targetGroup, List(DeploymentStep(List(RestartApplication(appNew)))), Timestamp.now())

    when(f.tracker.specInstancesLaunchedSync(app.id)).thenReturn(Seq.empty[Instance])

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)
      receiverProbe.expectMsg(DeploymentFinished(plan))
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  test("Scale with tasksToKill") {
    val f = new Fixture
    implicit val system = f.system
    val managerProbe = TestProbe()
    val receiverProbe = TestProbe()
    val app1 = AppDefinition(id = PathId("/app1"), cmd = Some("cmd"), instances = 3)
    val origGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app1.id -> app1))))

    val version2 = VersionInfo.forNewConfig(Timestamp(1000))
    val app1New = app1.copy(instances = 2, versionInfo = version2)

    val targetGroup = Group(PathId("/"), groups = Set(Group(PathId("/foo/bar"), Map(app1New.id -> app1New))))

    val instance1_1 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp.zero).getInstance()
    val instance1_2 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(500)).getInstance()
    val instance1_3 = TestInstanceBuilder.newBuilder(app1.id, version = app1.version).addTaskRunning(startedAt = Timestamp(1000)).getInstance()

    val plan = DeploymentPlan(original = origGroup, target = targetGroup, toKill = Map(app1.id -> Seq(instance1_2)))

    when(f.tracker.specInstancesLaunchedSync(app1.id)).thenReturn(Seq(instance1_1, instance1_2, instance1_3))

    try {
      f.deploymentActor(managerProbe.ref, receiverProbe.ref, plan)

      plan.steps.zipWithIndex.foreach {
        case (step, num) => managerProbe.expectMsg(5.seconds, DeploymentStepInfo(plan, step, num + 1))
      }

      managerProbe.expectMsg(5.seconds, DeploymentFinished(plan))

      f.killService.numKilled should be (1)
      f.killService.killed should contain (instance1_2.instanceId)
      verifyNoMoreInteractions(f.driver)
    } finally {
      Await.result(system.terminate(), Duration.Inf)
    }
  }

  class Fixture {
    implicit val system = ActorSystem("TestSystem")
    val tracker: InstanceTracker = mock[InstanceTracker]
    val queue: LaunchQueue = mock[LaunchQueue]
    val driver: SchedulerDriver = mock[SchedulerDriver]
    val killService = new KillServiceMock(system)
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val storage: StorageProvider = mock[StorageProvider]
    val hcManager: HealthCheckManager = mock[HealthCheckManager]
    val config: UpgradeConfig = mock[UpgradeConfig]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
    config.killBatchSize returns 100
    config.killBatchCycle returns 10.seconds

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      instance.instanceId returns instanceId
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def deploymentActor(manager: ActorRef, receiver: ActorRef, plan: DeploymentPlan) = TestActorRef(
      DeploymentActor.props(
        manager,
        receiver,
        driver,
        killService,
        scheduler,
        plan,
        tracker,
        queue,
        storage,
        hcManager,
        system.eventStream,
        readinessCheckExecutor
      )
    )

  }
}
