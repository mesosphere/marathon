package mesosphere.marathon
package core.health.impl

import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.InstanceHealthChanged
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.{AddHealthCheck, ApplicationKey, HealthCheckStatusChanged, RemoveHealthCheck}
import mesosphere.marathon.core.health.{Health, MarathonHttpHealthCheck, PortReference}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

class AppHealthCheckActorTest extends AkkaUnitTest {
  class Fixture {
    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val appKey = ApplicationKey(appId, appVersion)
    val hcPort80 = MarathonHttpHealthCheck(portIndex = Some(PortReference(80)))
    val hcPort443 = MarathonHttpHealthCheck(portIndex = Some(PortReference(443)))
    val instances = List(
      Instance.Id("instance1"),
      Instance.Id("instance2"),
      Instance.Id("instance3")
    )
  }

  "AppHealthCheckActor" should {
    "send status changed event when all instances are healthy" in {
      val f = new Fixture
      val systemLog = TestProbe()

      val actor = system.actorOf(AppHealthCheckActor.props(system.eventStream))
      system.eventStream.subscribe(systemLog.ref, classOf[InstanceHealthChanged])

      actor ! AddHealthCheck(f.appKey, f.hcPort80)
      actor ! AddHealthCheck(f.appKey, f.hcPort443)

      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      actor ! RemoveHealthCheck(f.appKey, f.hcPort80)
      actor ! RemoveHealthCheck(f.appKey, f.hcPort443)

      systemLog.expectMsg(InstanceHealthChanged(
        f.instances.head, f.appKey.version, f.appKey.appId, Some(true)))
    }

    "send status changed event when one instance becomes unhealthy" in {
      val f = new Fixture
      val systemLog = TestProbe()

      val actor = system.actorOf(AppHealthCheckActor.props(system.eventStream))
      system.eventStream.subscribe(systemLog.ref, classOf[InstanceHealthChanged])

      actor ! AddHealthCheck(f.appKey, f.hcPort80)
      actor ! AddHealthCheck(f.appKey, f.hcPort443)

      // all health checks pass once
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      // and suddenly one fails
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(8))))

      actor ! RemoveHealthCheck(f.appKey, f.hcPort80)
      actor ! RemoveHealthCheck(f.appKey, f.hcPort443)

      systemLog.expectMsg(InstanceHealthChanged(
        f.instances.head, f.appKey.version, f.appKey.appId, Some(true)))
      systemLog.expectMsg(InstanceHealthChanged(
        f.instances.head, f.appKey.version, f.appKey.appId, Some(false)))
    }

    "not send status changed even when health check is not registered" in {
      val f = new Fixture
      val systemLog = TestProbe()

      val actor = system.actorOf(AppHealthCheckActor.props(system.eventStream))
      system.eventStream.subscribe(systemLog.ref, classOf[InstanceHealthChanged])

      // all health checks pass once
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      systemLog.expectNoMsg()
    }

    "send status changed event when several instances become healthy" in {
      val f = new Fixture
      val systemLog = TestProbe()

      val actor = system.actorOf(AppHealthCheckActor.props(system.eventStream))
      system.eventStream.subscribe(systemLog.ref, classOf[InstanceHealthChanged])

      actor ! AddHealthCheck(f.appKey, f.hcPort80)
      actor ! AddHealthCheck(f.appKey, f.hcPort443)

      // all health checks pass once
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances(1), lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances.head, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      systemLog.expectMsg(InstanceHealthChanged(
        f.instances.head, f.appKey.version, f.appKey.appId, Some(true)))

      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances(1), lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      systemLog.expectMsg(InstanceHealthChanged(
        f.instances(1), f.appKey.version, f.appKey.appId, Some(true)))

      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort80,
        Health(f.instances(2), lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))
      actor ! HealthCheckStatusChanged(f.appKey, f.hcPort443,
        Health(f.instances(2), lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0))))

      systemLog.expectMsg(InstanceHealthChanged(
        f.instances(2), f.appKey.version, f.appKey.appId, Some(true)))

      actor ! RemoveHealthCheck(f.appKey, f.hcPort80)
      actor ! RemoveHealthCheck(f.appKey, f.hcPort443)
    }
  }
}
