package mesosphere.marathon.event.http

import akka.actor.{ ActorSystem, Props }
import akka.event.EventStream
import akka.testkit._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.event.http.HttpEventStreamActor.{ HttpEventStreamConnectionClosed, HttpEventStreamConnectionOpen }
import org.mockito.Mockito.{ when => call }
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

class HttpEventStreamActorTest extends TestKit(ActorSystem())
    with MarathonSpec with Matchers with GivenWhenThen with MockitoSugar with ImplicitSender with BeforeAndAfter {

  test("Register Handler") {
    Given("A handler that wants to connect")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")

    When("A connection open message is sent to the stream actor")
    streamActor ! HttpEventStreamConnectionOpen(handle)

    Then("An actor is created and subscribed to the event stream")
    streamActor.underlyingActor.clients should have size 1
    streamActor.underlyingActor.clients.get(handle) should be ('nonEmpty)
  }

  test("Unregister an already registered Handler") {
    Given("A registered handler")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")
    streamActor ! HttpEventStreamConnectionOpen(handle)
    streamActor.underlyingActor.clients should have size 1

    When("A connection closed message is sent to the stream actor")
    streamActor ! HttpEventStreamConnectionClosed(handle)

    Then("The actor is unsubscribed from the event stream")
    streamActor.underlyingActor.clients should have size 0
  }

  var streamActor: TestActorRef[HttpEventStreamActor] = _
  var stream: EventStream = _

  before {
    stream = mock[EventStream]
    streamActor = TestActorRef(Props(
      new HttpEventStreamActor(stream, 1)
    ))
  }
}
