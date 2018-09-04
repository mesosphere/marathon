package mesosphere.marathon
package core.heartbeat

import java.util.UUID

import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import org.apache.mesos.Protos._
import org.apache.mesos._

class MesosHeartbeatMonitorTest extends AkkaUnitTest {

  import MesosHeartbeatMonitorTest._

  class MonitorFactory {
    val heartbeatActor: TestProbe = TestProbe()
    val scheduler = mock[Scheduler]
    val reactor = mock[Heartbeat.Reactor]

    def newMonitor(withFakeReactor: Boolean = true): MesosHeartbeatMonitor =
      if (withFakeReactor)
        new MesosHeartbeatMonitor(scheduler, heartbeatActor.ref) {
          override def heartbeatReactor(driver: SchedulerDriver) = reactor
        }
      else
        new MesosHeartbeatMonitor(scheduler, heartbeatActor.ref)
  }

  "MesosHeartbeatMonitor" should {
    "MesosHeartbeatMonitor fully decorates Scheduler" in {
      val factory = new MonitorFactory
      val monitor = factory.newMonitor()

      monitor.registered(null, null, null)
      monitor.reregistered(null, null)
      monitor.resourceOffers(null, null)
      monitor.offerRescinded(null, null)
      monitor.statusUpdate(null, FakeStatus())
      monitor.frameworkMessage(null, null, null, null)
      monitor.disconnected(null)
      monitor.slaveLost(null, null)
      monitor.executorLost(null, null, null, 0)
      monitor.error(null, null)

      verify(factory.scheduler, times(1)).registered(any, any, any)
      verify(factory.scheduler, times(1)).reregistered(any, any)
      verify(factory.scheduler, times(1)).resourceOffers(any, any)
      verify(factory.scheduler, times(1)).offerRescinded(any, any)
      verify(factory.scheduler, times(1)).statusUpdate(any, any)
      verify(factory.scheduler, times(1)).frameworkMessage(any, any, any, any)
      verify(factory.scheduler, times(1)).disconnected(any)
      verify(factory.scheduler, times(1)).slaveLost(any, any)
      verify(factory.scheduler, times(1)).executorLost(any, any, any, any)
      verify(factory.scheduler, times(1)).error(any, any)

      // no interactions should result from these since they *should be* filtered status objects
      monitor.statusUpdate(null, FakeHeartbeatStatus())
      monitor.statusUpdate(null, FakeHeartbeatStatus(true))

      noMoreInteractions(factory.scheduler)
    }

    "MesosHeartbeatMonitor sends proper actor messages for Scheduler callbacks" in {
      val factory = new MonitorFactory
      val monitor = factory.newMonitor()

      // activation messages
      val registeredDriver = mock[SchedulerDriver]
      monitor.activate(registeredDriver)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(
        Heartbeat.MessageActivate(factory.reactor, MesosHeartbeatMonitor.sessionOf(registeredDriver)))

      val reregisteredDriver = mock[SchedulerDriver]
      monitor.reregistered(reregisteredDriver, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(
        Heartbeat.MessageActivate(factory.reactor, MesosHeartbeatMonitor.sessionOf(reregisteredDriver)))

      // deactivation messages
      val disconnectedDriver = mock[SchedulerDriver]
      monitor.disconnected(disconnectedDriver)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(
        Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(disconnectedDriver)))

      val errorDriver = mock[SchedulerDriver]
      monitor.error(errorDriver, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(
        Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(errorDriver)))

      // pulse messages
      monitor.resourceOffers(null, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.offerRescinded(null, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.statusUpdate(null, FakeStatus())
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.statusUpdate(null, FakeStatus(true))
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.frameworkMessage(null, null, null, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.slaveLost(null, null)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)

      monitor.executorLost(null, null, null, 0)
      factory.heartbeatActor.expectMsgType[Heartbeat.Message] should be(Heartbeat.MessagePulse)
    }

    "heartbeatReactor property handles skip and failure events" in {
      val factory = new MonitorFactory
      val monitor = factory.newMonitor(false)

      val fakeDriver = mock[SchedulerDriver]
      val reactor = monitor.heartbeatReactor(fakeDriver)

      reactor.onSkip(1)
      verify(fakeDriver, times(1)).reconcileTasks(any)

      reactor.onFailure()
      verify(factory.scheduler, times(1)).disconnected(fakeDriver)

      noMoreInteractions(fakeDriver)
      noMoreInteractions(factory.scheduler)
    }
  }
}

object MesosHeartbeatMonitorTest {

  import MesosHeartbeatMonitor._

  def FakeStatus(unknown: Boolean = false) = TaskStatus.newBuilder
    .setTaskId(TaskID.newBuilder.setValue(UUID.randomUUID().toString))
    .setState(if (unknown) TaskState.TASK_UNKNOWN else TaskState.TASK_LOST)
    .setSlaveId(SlaveID.newBuilder.setValue(UUID.randomUUID().toString))
    .build

  def FakeHeartbeatStatus(unknown: Boolean = false) = TaskStatus.newBuilder
    .setTaskId(TaskID.newBuilder.setValue(FAKE_TASK_PREFIX + UUID.randomUUID().toString))
    .setState(if (unknown) TaskState.TASK_UNKNOWN else TaskState.TASK_LOST)
    .setSlaveId(SlaveID.newBuilder.setValue(FAKE_AGENT_PREFIX + UUID.randomUUID().toString))
    .setSource(TaskStatus.Source.SOURCE_MASTER)
    .setReason(TaskStatus.Reason.REASON_RECONCILIATION)
    .build
}
