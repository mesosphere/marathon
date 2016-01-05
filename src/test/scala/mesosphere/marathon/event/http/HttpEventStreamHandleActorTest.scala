package mesosphere.marathon.event.http

import java.io.EOFException
import java.util.concurrent.CountDownLatch

import akka.actor.{ ActorSystem, Props }
import akka.event.EventStream
import akka.testkit.{ EventFilter, ImplicitSender, TestActorRef, TestKit }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.event.{ EventStreamAttached, EventStreamDetached, Subscribe }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class HttpEventStreamHandleActorTest extends MarathonActorSupport
    with MarathonSpec with Matchers with GivenWhenThen with ImplicitSender with BeforeAndAfter with Mockito {

  test("A message send to the handle actor will be transferred to the stream handle") {
    Given("A handler that will postpone sending until latch is hit")
    val latch = new CountDownLatch(1)
    handle.sendEvent(any[String], any[String]) answers (_ => latch.countDown())

    When("The event is send to the actor, the outstanding messages is 1")
    handleActor ! EventStreamAttached("remote")

    Then("We need to wait for the future to succeed")
    awaitCond(latch.getCount == 0)
    verify(handle, times(1)).sendEvent(any[String], any[String])
  }

  test("If the consumer is slow and maxOutstanding limit is reached, messages get dropped") {
    Given("A handler that will postpone the sending")
    val latch = new CountDownLatch(1)
    handle.sendEvent(any[String], any[String]) answers (_ => latch.await())
    val filter = EventFilter(pattern = "Ignore event.*", occurrences = 1)

    When("More than the max size of outstanding events is send to the actor")
    handleActor ! EventStreamAttached("remote") //send immediately
    handleActor ! EventStreamAttached("remote") //put to queue
    handleActor ! EventStreamAttached("remote") //drop event

    Then("Only one message is send to the handler")
    latch.countDown()
    filter.awaitDone(1.second)
  }

  test("If the handler throws an EOF exception, the actor stops acting") {
    Given("A handler that will postpone the sending")
    handle.sendEvent(any[String], any[String]) answers { _ => throw new EOFException() }
    val filter = EventFilter(pattern = "Received EOF.*", occurrences = 1)

    When("An event is send to actor")
    handleActor ! EventStreamAttached("remote")

    Then("The actor does not understand any messages")
    filter.awaitDone(1.second)
  }

  test("Multiple messages are send in order") {
    Given("A handler that will postpone the sending")
    val latch = new CountDownLatch(1)
    var events = List.empty[String]
    handle.sendEvent(any[String], any[String]) answers { args => events ::= args(0).asInstanceOf[String]; latch.await() }
    handleActor = TestActorRef(Props(
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
    handleActor.underlyingActor.outstanding should be (subscribe :: detached :: Nil)

    When("The first event is delivered")
    latch.countDown()

    Then("All events are transferred in correct order")
    awaitCond(events.size == 3)
    events.reverse should be ("event_stream_attached" :: "event_stream_detached" :: "subscribe_event" :: Nil)
  }

  var handleActor: TestActorRef[HttpEventStreamHandleActor] = _
  var stream: EventStream = _
  var handle: HttpEventStreamHandle = _

  before {
    handle = mock[HttpEventStreamHandle]
    stream = mock[EventStream]
    handleActor = TestActorRef(Props(
      new HttpEventStreamHandleActor(handle, stream, 1)
    ))
  }
}
