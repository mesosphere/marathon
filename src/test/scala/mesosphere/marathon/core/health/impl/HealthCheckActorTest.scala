package mesosphere.marathon
package core.health.impl

import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{MergeHub, Sink}
import akka.testkit._
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.PurgeHealthCheckStatuses
import mesosphere.marathon.core.health.{Health, HealthCheck, Healthy, MarathonHealthCheck, MarathonHttpHealthCheck, PortReference}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import org.mockito.Mockito.verifyNoMoreInteractions

import scala.concurrent.Future
import scala.concurrent.duration._

class HealthCheckActorTest extends AkkaUnitTest {
  class Fixture {
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val instanceTracker = mock[InstanceTracker]

    val appId = AbsolutePathId("/test")
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId, role = "*")
    val killService: KillService = mock[KillService]

    val scheduler: MarathonScheduler = mock[MarathonScheduler]

    val instanceBuilder = TestInstanceBuilder.newBuilder(appId, version = appVersion).addTaskRunning()
    val instance = instanceBuilder.getInstance()

    val appHealthCheckActor = TestProbe()
    val healthCheck = MarathonHttpHealthCheck(portIndex = Some(PortReference(0)), interval = 1.second)
    val task: Task = instance.appTask

    val unreachableInstance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
    val lostInstance = TestInstanceBuilder.newBuilder(appId).addTaskLost().getInstance()

    val healthCheckWorkerHub: Sink[(AppDefinition, Instance, MarathonHealthCheck, ActorRef), NotUsed] =
      MergeHub
        .source[(AppDefinition, Instance, MarathonHealthCheck, ActorRef)](1)
        .map { case (_, instance, _, ref) => ref ! Healthy(instance.instanceId, Timestamp.now()) }
        .to(Sink.ignore)
        .run()

    def actor(healthCheck: HealthCheck) =
      TestActorRef[HealthCheckActor](
        Props(
          new HealthCheckActor(
            app,
            appHealthCheckActor.ref,
            killService,
            healthCheck,
            instanceTracker,
            system.eventStream,
            healthCheckWorkerHub
          )
        )
      )

    def healthCheckActor() =
      TestActorRef[HealthCheckActor](
        Props(
          new HealthCheckActor(
            app,
            appHealthCheckActor.ref,
            killService,
            healthCheck,
            instanceTracker,
            system.eventStream,
            healthCheckWorkerHub
          ) {}
        )
      )
  }

  "HealthCheckActor" should {
    //regression test for #934
    "should not dispatch health checks for staging tasks" in new Fixture {
      instanceTracker.specInstances(any, anyBoolean)(any) returns Future.successful(Seq(instance))

      healthCheckActor()

      appHealthCheckActor.expectMsgAllClassOf(classOf[PurgeHealthCheckStatuses])
    }

    "should not dispatch health checks for lost tasks" in new Fixture {
      instanceTracker.specInstances(any, anyBoolean)(any) returns Future.successful(Seq(lostInstance))

      healthCheckActor()

      appHealthCheckActor.expectMsgAllClassOf(classOf[PurgeHealthCheckStatuses])
    }

    "should not dispatch health checks for unreachable tasks" in new Fixture {
      instanceTracker.specInstances(any, anyBoolean)(any) returns Future.successful(Seq(unreachableInstance))

      healthCheckActor()

      appHealthCheckActor.expectMsgAllClassOf(classOf[PurgeHealthCheckStatuses])
    }

    // regression test for #1456
    "task should be killed if health check fails" in {
      val f = new Fixture
      val actor = f.actor(MarathonHttpHealthCheck(maxConsecutiveFailures = 3, portIndex = Some(PortReference(0))))

      actor.underlyingActor.checkConsecutiveFailures(f.instance, Health(f.instance.instanceId, consecutiveFailures = 3))
      verify(f.killService).killInstancesAndForget(Seq(f.instance), KillReason.FailedHealthChecks)
      verifyNoMoreInteractions(f.instanceTracker, f.scheduler)
    }

    "task should not be killed if health check fails, but the task is unreachable" in {
      val f = new Fixture
      val actor = f.actor(MarathonHttpHealthCheck(maxConsecutiveFailures = 3, portIndex = Some(PortReference(0))))

      actor.underlyingActor
        .checkConsecutiveFailures(f.unreachableInstance, Health(f.unreachableInstance.instanceId, consecutiveFailures = 3))
      verifyNoMoreInteractions(f.instanceTracker, f.scheduler)
    }
  }
}
