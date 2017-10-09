package mesosphere.marathon
package core.event.impl.stream

import akka.actor.{ Props, Terminated }
import akka.event.EventStream
import akka.testkit._
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.election.LeadershipState
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor._
import mesosphere.marathon.stream.EnrichedSource
import org.mockito.Mockito.{ when => call, _ }

import scala.concurrent.duration._

class HttpEventStreamActorTest extends AkkaUnitTest with ImplicitSender {

  case class Fixture(
      stream: EventStream = mock[EventStream],
      metrics: HttpEventStreamActorMetrics = new HttpEventStreamActorMetrics()) {

    def handleStreamProps(handle: HttpEventStreamHandle) = Props(new HttpEventStreamHandleActor(handle, stream, 1))
    val streamActor: TestActorRef[HttpEventStreamActor] = TestActorRef(Props(
      new HttpEventStreamActor(EnrichedSource.emptyCancellable, metrics, handleStreamProps)
    ))
  }

  "HttpEventStreamActor" should {
    "Register Handler" in new Fixture {
      Given("A handler that wants to connect and we have an active streamActor")
      val handle = mock[HttpEventStreamHandle]
      call(handle.id).thenReturn("1")
      streamActor ! LeadershipState.ElectedAsLeader

      When("A connection open message is sent to the stream actor")
      streamActor ! HttpEventStreamConnectionOpen(handle)

      Then("An actor is created and subscribed to the event stream")
      streamActor.underlyingActor.streamHandleActors should have size 1
      streamActor.underlyingActor.streamHandleActors.get(handle) should be ('nonEmpty)
    }

    "Unregister handlers when switching to standby mode" in new Fixture {
      Given("A handler that wants to connect and we have an active streamActor with one connection")
      val handle = mock[HttpEventStreamHandle]
      call(handle.id).thenReturn("1")
      streamActor ! LeadershipState.ElectedAsLeader
      streamActor ! HttpEventStreamConnectionOpen(handle)
      val handleActor = streamActor.underlyingActor.streamHandleActors.values.head
      watch(handleActor)

      When("The stream actor switches to standby mode")
      streamActor ! LeadershipState.Standby(None)

      Then("All handler actors are stopped and the connection is closed")
      val terminated = expectMsgClass(1.second, classOf[Terminated])
      terminated.getActor should be(handleActor)
      streamActor.underlyingActor.streamHandleActors should have size 0
      streamActor.underlyingActor.streamHandleActors.get(handle) should be ('empty)
      verify(handle).close()
    }

    "Close connection immediately if we are in standby mode" in new Fixture {
      Given("A handler that wants to connect")
      val handle = mock[HttpEventStreamHandle]("handle")

      When("A connection open message is sent to the stream actor in standby mode")
      streamActor ! HttpEventStreamConnectionOpen(handle)

      Then("The connection is immediately closed without creating an actor")
      streamActor.underlyingActor.streamHandleActors should have size 0
      streamActor.underlyingActor.streamHandleActors.get(handle) should be ('empty)
      verify(handle).close()
      verifyNoMoreInteractions(handle)
    }

    "Unregister an already registered Handler" in new Fixture {
      Given("A registered handler")
      val handle = mock[HttpEventStreamHandle]
      call(handle.id).thenReturn("1")
      streamActor ! LeadershipState.ElectedAsLeader
      streamActor ! HttpEventStreamConnectionOpen(handle)
      streamActor.underlyingActor.streamHandleActors should have size 1

      When("A connection closed message is sent to the stream actor")
      streamActor ! HttpEventStreamConnectionClosed(handle)

      Then("The actor is unsubscribed from the event stream")
      streamActor.underlyingActor.streamHandleActors should have size 0
    }
  }
}
