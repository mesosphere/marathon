package mesosphere.marathon.event.http

import java.io.{ IOException, EOFException }
import java.util.concurrent.CountDownLatch

import akka.actor.{ Actor, ActorSystem, Props }
import akka.event.EventStream
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.event.EventStreamAttached
import org.mockito.Matchers.any
import org.mockito.Mockito.{ times, verify, when => call }
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

class HttpEventStreamHandleActorTest extends TestKit(ActorSystem())
    with MarathonSpec with Matchers with GivenWhenThen with MockitoSugar with ImplicitSender with BeforeAndAfter {

  test("A message send to the handle actor will be transferred to the stream handle") {
    Given("A handler that will postpone sending until latch is hit")
    val latch = new CountDownLatch(1)
    call(handle.sendMessage(any[String])).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = latch.await()
    })

    When("The event is send to the actor, the outstanding messages is 1")
    handleActor ! EventStreamAttached("remote")
    awaitCond(handleActor.underlyingActor.outstanding == 1)

    Then("We need to wait for the future to succeed")
    latch.countDown()
    awaitCond(handleActor.underlyingActor.outstanding == 0)
    verify(handle, times(1)).sendMessage(any[String])
  }

  test("If the consumer is slow and maxOutstanding limit is reached, messages get dropped") {
    Given("A handler that will postpone the sending")
    val latch = new CountDownLatch(1)
    call(handle.sendMessage(any[String])).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = latch.await()
    })

    When("More than the max size of outstanding events is send to the actor")
    handleActor ! EventStreamAttached("remote")
    handleActor ! EventStreamAttached("remote")
    handleActor ! EventStreamAttached("remote")
    handleActor ! EventStreamAttached("remote")

    Then("Only one message is send to the handler")
    latch.countDown()
    awaitCond(handleActor.underlyingActor.outstanding == 0)
    verify(handle, times(1)).sendMessage(any[String])
  }

  test("If the handler throws an EOF exception, the actor stops acting") {
    Given("A handler that will postpone the sending")
    val latch = new CountDownLatch(1)
    call(handle.sendMessage(any[String])).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        val ex = latch.getCount == 0
        latch.await()
        if (ex) throw new EOFException()
      }
    })

    When("An event is send to actor")
    handleActor ! EventStreamAttached("remote")
    awaitCond(handleActor.underlyingActor.outstanding == 1)

    Then("The actor does not understand any messages")
    latch.countDown()
    awaitCond(handleActor.underlyingActor.outstanding == 0)
    handleActor ! EventStreamAttached("remote")
    handleActor ! EventStreamAttached("remote")
    awaitCond(handleActor.underlyingActor.outstanding == 0)
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
