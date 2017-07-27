package mesosphere.marathon
package core.task.update.impl.steps

import akka.event.EventStream
import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ InstanceHealthChanged, MarathonEvent }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.UnreachableStrategy

import scala.collection.immutable.Seq

class PostToEventStreamStepImplTest extends UnitTest {

  "PostToEventStreamStepImpl" when {

    val f = new Fixture

    "create" should {
      "give the correct name" in { f.step.name should be("postTaskStatusEvent") }
    }

    "processing an instance change" should {
      val f = new Fixture
      val events: Seq[MarathonEvent] = Seq(f.event1, f.event2)
      val instanceChange: InstanceChange = InstanceUpdated(f.instance, None, events)

      f.step.process(instanceChange).futureValue

      "post each event to the event stream and not a health changed event" in {
        verify(f.eventStream, once).publish(f.event1)
        verify(f.eventStream, once).publish(f.event2)
        noMoreInteractions(f.eventStream)
      }
    }

    "processing an instance change with new health" should {
      val f = new Fixture
      val events: Seq[MarathonEvent] = Seq(f.event1, f.event2)
      val lastState = InstanceState(Condition.Running, f.clock.now(), Some(f.clock.now()), healthy = None)
      val newState = InstanceState(Condition.Failed, f.clock.now(), activeSince = None, healthy = Some(false))
      val instance = f.instance.copy(state = newState)
      val instanceChange: InstanceChange = InstanceUpdated(instance, Some(lastState), events)

      f.step.process(instanceChange).futureValue

      "post each event to the event stream and a health changed event" in {
        val expectedHealthChange = InstanceHealthChanged(instanceChange.id, instanceChange.runSpecVersion,
          instanceChange.runSpecId, Some(false))

        verify(f.eventStream, once).publish(f.event1)
        verify(f.eventStream, once).publish(f.event2)
        verify(f.eventStream, once).publish(expectedHealthChange)
        noMoreInteractions(f.eventStream)
      }
    }

    "processing an instance change with new health but without a last state" should {
      val f = new Fixture
      val events: Seq[MarathonEvent] = Seq(f.event1, f.event2)
      val newState = InstanceState(Condition.Failed, f.clock.now(), activeSince = None, healthy = Some(false))
      val instance = f.instance.copy(state = newState)
      val instanceChange: InstanceChange = InstanceUpdated(instance, None, events)

      f.step.process(instanceChange).futureValue

      "post each event to the event stream and a health changed event" in {
        val expectedHealthChange = InstanceHealthChanged(instanceChange.id, instanceChange.runSpecVersion,
          instanceChange.runSpecId, Some(false))

        verify(f.eventStream, once).publish(f.event1)
        verify(f.eventStream, once).publish(f.event2)
        verify(f.eventStream, once).publish(expectedHealthChange)
        noMoreInteractions(f.eventStream)
      }
    }

    "processing an instance change without new health but with a last state" should {
      val f = new Fixture
      val events: Seq[MarathonEvent] = Seq(f.event1, f.event2)
      val lastState = InstanceState(Condition.Running, f.clock.now(), Some(f.clock.now()), Some(true))
      val newState = InstanceState(Condition.Failed, f.clock.now(), activeSince = None, healthy = None)
      val instance = f.instance.copy(state = newState)
      val instanceChange: InstanceChange = InstanceUpdated(instance, Some(lastState), events)

      f.step.process(instanceChange).futureValue

      "post each event to the event stream and a health changed event" in {
        val expectedHealthChange = InstanceHealthChanged(instanceChange.id, instanceChange.runSpecVersion,
          instanceChange.runSpecId, None)

        verify(f.eventStream, once).publish(f.event1)
        verify(f.eventStream, once).publish(f.event2)
        verify(f.eventStream, once).publish(expectedHealthChange)
        noMoreInteractions(f.eventStream)
      }
    }
  }

  class Fixture {
    val clock = new SettableClock()

    val event1 = mock[MarathonEvent]
    val event2 = mock[MarathonEvent]

    val agentInfo = Instance.AgentInfo("localhost", None, Seq.empty)
    val instanceState = InstanceState(Condition.Running, clock.now(), Some(clock.now()), healthy = None)
    val instance = Instance(
      Instance.Id("foobar.instance-baz"), agentInfo, instanceState, Map.empty, clock.now(),
      UnreachableStrategy.default()
    )
    val eventStream = mock[EventStream]

    val step = new PostToEventStreamStepImpl(eventStream)
  }
}
