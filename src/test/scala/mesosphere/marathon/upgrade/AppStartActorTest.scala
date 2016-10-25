package mesosphere.marathon.upgrade

import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ DeploymentStatus, InstanceChanged, InstanceHealthChanged }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec, MarathonTestHelper, Mockito }
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }
import org.apache.mesos.SchedulerDriver
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class AppStartActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with Mockito {

  test("Without Health Checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    system.eventStream.publish(f.instanceChanged(app, Condition.Running)) // linter:ignore:IdenticalStatements
    system.eventStream.publish(f.instanceChanged(app, Condition.Running))

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startRunSpec(app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("With Health Checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 10,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    system.eventStream.publish(f.healthChanged(app, healthy = true)) // linter:ignore:IdenticalStatements
    system.eventStream.publish(f.healthChanged(app, healthy = true))

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startRunSpec(app.copy(instances = 2))
    expectTerminated(ref)
  }

  test("Failed") {
    val f = new Fixture
    f.scheduler.stopRunSpec(any).asInstanceOf[Future[Unit]] returns Future.successful(())

    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 2, promise)
    watch(ref)

    ref ! DeploymentActor.Shutdown

    intercept[AppStartCanceledException] {
      Await.result(promise.future, 5.seconds)
    }

    verify(f.scheduler).startRunSpec(app.copy(instances = 2))
    verify(f.scheduler).stopRunSpec(app)
    expectTerminated(ref)
  }

  test("No tasks to start without health checks") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/app"), instances = 10)
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startRunSpec(app.copy(instances = 0))
    expectTerminated(ref)
  }

  test("No tasks to start with health checks") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 10,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
    val promise = Promise[Unit]()
    val ref = f.startActor(app, scaleTo = 0, promise)
    watch(ref)

    Await.result(promise.future, 5.seconds)

    verify(f.scheduler).startRunSpec(app.copy(instances = 0))
    expectTerminated(ref)
  }

  class Fixture {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val taskTracker: InstanceTracker = MarathonTestHelper.createTaskTracker(AlwaysElectedLeadershipModule.forActorSystem(system))
    val deploymentManager: TestProbe = TestProbe()
    val deploymentStatus: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    def instanceChanged(app: AppDefinition, condition: Condition): InstanceChanged = {
      val instanceId = Instance.Id.forRunSpec(app.id)
      val instance: Instance = mock[Instance]
      instance.instanceId returns instanceId
      InstanceChanged(instanceId, app.version, app.id, condition, instance)
    }

    def healthChanged(app: AppDefinition, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(Instance.Id.forRunSpec(app.id), app.version, app.id, healthy = Some(healthy))
    }

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[AppStartActor] =
      TestActorRef(AppStartActor.props(deploymentManager.ref, deploymentStatus, driver, scheduler,
        launchQueue, taskTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, promise)
      )
  }
}
