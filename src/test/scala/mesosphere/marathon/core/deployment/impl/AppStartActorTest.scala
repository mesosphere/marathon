package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ DeploymentStatus, InstanceChanged, InstanceHealthChanged }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.MarathonTestHelper
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class AppStartActorTest extends AkkaUnitTest {
  "AppStartActor" should {
    "Without Health Checks" in {
      val f = new Fixture
      val app = AppDefinition(id = f.appId, instances = 10)
      val promise = Promise[Unit]()
      val ref = f.startActor(app, scaleTo = 2, promise)
      watch(ref)

      system.eventStream.publish(f.instanceChanged(app, Condition.Running)) // linter:ignore:IdenticalStatements
      system.eventStream.publish(f.instanceChanged(app, Condition.Running))

      promise.future.futureValue(Timeout(5.seconds))

      verify(f.scheduler).startRunSpec(app.copy(instances = 2))
      expectTerminated(ref)
    }

    "With Health Checks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = f.appId,
        instances = 10,
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
      val promise = Promise[Unit]()
      val ref = f.startActor(app, scaleTo = 2, promise)
      watch(ref)

      system.eventStream.publish(f.healthChanged(app, healthy = true)) // linter:ignore:IdenticalStatements
      system.eventStream.publish(f.healthChanged(app, healthy = true))

      promise.future.futureValue(Timeout(5.seconds))

      verify(f.scheduler).startRunSpec(app.copy(instances = 2))
      expectTerminated(ref)
    }

    "No tasks to start without health checks" in {
      val f = new Fixture
      val app = AppDefinition(id = f.appId, instances = 10)
      val promise = Promise[Unit]()
      val ref = f.startActor(app, scaleTo = 0, promise)
      watch(ref)

      promise.future.futureValue(Timeout(5.seconds))

      verify(f.scheduler).startRunSpec(app.copy(instances = 0))
      expectTerminated(ref)
    }

    "No tasks to start with health checks" in {
      val f = new Fixture
      val app = AppDefinition(
        id = f.appId,
        instances = 10,
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
      val promise = Promise[Unit]()
      val ref = f.startActor(app, scaleTo = 0, promise)
      watch(ref)

      promise.future.futureValue(Timeout(5.seconds))

      verify(f.scheduler).startRunSpec(app.copy(instances = 0))
      expectTerminated(ref)
    }

    class Fixture {

      val scheduler: SchedulerActions = mock[SchedulerActions]
      val launchQueue: LaunchQueue = mock[LaunchQueue]
      val instanceTracker: InstanceTracker = MarathonTestHelper.createTaskTracker(
        AlwaysElectedLeadershipModule.forRefFactory(system))
      val deploymentManager: TestProbe = TestProbe()
      val deploymentStatus: DeploymentStatus = mock[DeploymentStatus]
      val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]
      val appId = PathId("/app")

      launchQueue.get(appId) returns None
      scheduler.startRunSpec(any) returns Future.successful(Done)

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
        TestActorRef(AppStartActor.props(deploymentManager.ref, deploymentStatus, scheduler,
          launchQueue, instanceTracker, system.eventStream, readinessCheckExecutor, app, scaleTo, Seq.empty, promise)
        )
    }
  }
}
