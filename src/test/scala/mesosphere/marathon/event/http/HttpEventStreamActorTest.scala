package mesosphere.marathon.event.http

import akka.actor.{ Terminated, ActorSystem, Props }
import akka.event.EventStream
import akka.testkit._
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.event.LocalLeadershipEvent
import mesosphere.marathon.event.http.HttpEventStreamActor.{ HttpEventStreamConnectionClosed, HttpEventStreamConnectionOpen }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.MarathonActorSupport
import org.mockito.Mockito.{ when => call, verify, verifyNoMoreInteractions }
import org.scalatest.mock.MockitoSugar
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
  var leaderInfo: LeaderInfo = _
  var stream: EventStream = _
  var metrics: HttpEventStreamActorMetrics = _

  before {
    leaderInfo = mock[LeaderInfo]
    stream = mock[EventStream]
    metrics = new HttpEventStreamActorMetrics(new Metrics(new MetricRegistry))
    def handleStreamProps(handle: HttpEventStreamHandle) = Props(new HttpEventStreamHandleActor(handle, stream, 1))
    streamActor = TestActorRef(Props(
      new HttpEventStreamActor(leaderInfo, metrics, handleStreamProps)
    ))
  }
}
