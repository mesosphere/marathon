package mesosphere.marathon
package core.event.impl.stream

import java.io.EOFException
import java.util.concurrent.CountDownLatch

import akka.actor.Props
import akka.event.EventStream
import akka.testkit.{ EventFilter, ImplicitSender, TestActorRef }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.{ EventStreamAttached, EventStreamDetached, MarathonEvent, Subscribe }

import scala.concurrent.duration._

class HttpEventStreamHandleActorTest extends AkkaUnitTest with ImplicitSender {
  case class Fixture(
      handle: HttpEventStreamHandle = mock[HttpEventStreamHandle],
      stream: EventStream = mock[EventStream]) {
    val handleActor: TestActorRef[HttpEventStreamHandleActor] = TestActorRef(Props(
      new HttpEventStreamHandleActor(handle, stream, 1)
    ))
  }
  "HttpEventStreamHandleActor" should {
    "A message send to the handle actor will be transferred to the stream handle" in new Fixture {
      Given("A handler that will postpone sending until latch is hit")
      val latch = new CountDownLatch(1)
      handle.sendEvent(any[MarathonEvent]) answers (_ => latch.countDown())

      When("The event is send to the actor, the outstanding messages is 1")
      handleActor ! EventStreamAttached("remote")

      Then("We need to wait for the future to succeed")
      awaitCond(latch.getCount == 0)
      verify(handle, times(1)).sendEvent(any[MarathonEvent])
    }

    "If the consumer is slow and maxOutstanding limit is reached, messages get dropped" in new Fixture {
      Given("A handler that will postpone the sending")
      val latch = new CountDownLatch(1)
      handle.sendEvent(any[MarathonEvent]) answers (_ => latch.await())
      val filter = EventFilter(pattern = "Ignore event.*", occurrences = 1)

      When("More than the max size of outstanding events is send to the actor")
      handleActor ! EventStreamAttached("remote") // linter:ignore:IdenticalStatements //send immediately
      handleActor ! EventStreamAttached("remote") //put to queue
      handleActor ! EventStreamAttached("remote") //drop event

      Then("Only one message is send to the handler")
      latch.countDown()
      filter.awaitDone(1.second)
    }

    "If the handler throws an EOF exception, the actor stops acting" in new Fixture {
      Given("A handler that will postpone the sending")
      handle.sendEvent(any[MarathonEvent]) answers { _ => throw new EOFException() }
      val filter = EventFilter(pattern = "Received EOF.*", occurrences = 1)

      When("An event is send to actor")
      handleActor ! EventStreamAttached("remote")

      Then("The actor does not understand any messages")
      filter.awaitDone(1.second)
    }

    "Multiple messages are send in order" in {
      Given("A handler that will postpone the sending")
      val latch = new CountDownLatch(1)
      var events = List.empty[String]
      val handle = mock[HttpEventStreamHandle]
      val stream = mock[EventStream]
      handle.sendEvent(any[MarathonEvent]) answers { args => events ::= args(0).asInstanceOf[MarathonEvent].eventType; latch.await() }
      val handleActor: TestActorRef[HttpEventStreamHandleActor] = TestActorRef(Props(
        new HttpEventStreamHandleActor(handle, stream, 50)
      ))
      val attached = EventStreamAttached("remote")
      val detached = EventStreamDetached("remote")
      val subscribe = Subscribe("ip", "url")

      When("One message is send directly and 2 are queued for later delivery")
      handleActor ! attached
      handleActor ! detached
      handleActor ! subscribe

      Then("The actor stores the events in reverse order")
      handleActor.underlyingActor.outstanding should be(subscribe :: detached :: Nil)

      When("The first event is delivered")
      latch.countDown()

      Then("All events are transferred in correct order")
      awaitCond(events.size == 3)
      events.reverse should be("event_stream_attached" :: "event_stream_detached" :: "subscribe_event" :: Nil)
    }
  }
}
