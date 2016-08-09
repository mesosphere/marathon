package mesosphere.marathon.core.event.impl.stream

import akka.actor.{ Props, Terminated }
import akka.event.EventStream
import akka.testkit._
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test._
import org.mockito.Mockito.{ when => call, _ }
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class HttpEventStreamActorTest extends MarathonActorSupport
    with MarathonSpec with Matchers with GivenWhenThen with MockitoSugar with ImplicitSender with BeforeAndAfter {

  test("Register Handler") {
    Given("A handler that wants to connect and we have an active streamActor")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")
    streamActor ! LocalLeadershipEvent.ElectedAsLeader

    When("A connection open message is sent to the stream actor")
    streamActor ! HttpEventStreamConnectionOpen(handle)

    Then("An actor is created and subscribed to the event stream")
    streamActor.underlyingActor.streamHandleActors should have size 1
    streamActor.underlyingActor.streamHandleActors.get(handle) should be ('nonEmpty)
  }

  test("Unregister handlers when switching to standby mode") {
    Given("A handler that wants to connect and we have an active streamActor with one connection")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")
    streamActor ! LocalLeadershipEvent.ElectedAsLeader
    streamActor ! HttpEventStreamConnectionOpen(handle)
    val handleActor = streamActor.underlyingActor.streamHandleActors.values.head
    watch(handleActor)

    When("The stream actor switches to standby mode")
    streamActor ! LocalLeadershipEvent.Standby

    Then("All handler actors are stopped and the connection is closed")
    val terminated = expectMsgClass(1.second, classOf[Terminated])
    terminated.getActor should be(handleActor)
    streamActor.underlyingActor.streamHandleActors should have size 0
    streamActor.underlyingActor.streamHandleActors.get(handle) should be ('empty)
    verify(handle).close()
  }

  test("Close connection immediately if we are in standby mode") {
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

  test("Unregister an already registered Handler") {
    Given("A registered handler")
    val handle = mock[HttpEventStreamHandle]
    call(handle.id).thenReturn("1")
    streamActor ! LocalLeadershipEvent.ElectedAsLeader
    streamActor ! HttpEventStreamConnectionOpen(handle)
    streamActor.underlyingActor.streamHandleActors should have size 1

    When("A connection closed message is sent to the stream actor")
    streamActor ! HttpEventStreamConnectionClosed(handle)

    Then("The actor is unsubscribed from the event stream")
    streamActor.underlyingActor.streamHandleActors should have size 0
  }

  var streamActor: TestActorRef[HttpEventStreamActor] = _
  var electionService: ElectionService = _
  var stream: EventStream = _
  var metrics: HttpEventStreamActorMetrics = _

  before {
    electionService = mock[ElectionService]
    stream = mock[EventStream]
    metrics = new HttpEventStreamActorMetrics(new Metrics(new MetricRegistry))
    def handleStreamProps(handle: HttpEventStreamHandle) = Props(new HttpEventStreamHandleActor(handle, stream, 1))
    streamActor = TestActorRef(Props(
      new HttpEventStreamActor(electionService, metrics, handleStreamProps)
    ))
  }
}
