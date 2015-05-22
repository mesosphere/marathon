package mesosphere.marathon.event.http

import akka.actor.{ ActorSystem, Props }
import akka.event.EventStream
import akka.testkit._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.event.http.HttpEventStreamActor.{ HttpEventStreamConnectionClosed, HttpEventStreamConnectionOpen, HttpEventStreamIncomingMessage }
import org.mockito.Mockito.{ when => call, verify }
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }
import play.api.libs.json.Json

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

  test("An incoming message will be replied") {
    Given("A registered handler")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")
    streamActor ! HttpEventStreamConnectionOpen(handle)
    streamActor.underlyingActor.clients should have size 1
    val message = "Hello"

    When("An incoming message is send")
    streamActor ! HttpEventStreamIncomingMessage(handle, message)

    Then("A response is send to the handle")
    verify(handle).sendMessage(Json.stringify(Json.obj("received" -> message)))
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
