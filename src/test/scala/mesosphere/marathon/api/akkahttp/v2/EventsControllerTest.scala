package mesosphere.marathon
package api.akkahttp.v2

import akka.event.EventStream
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.headers.`X-Real-Ip`
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, RemoteAddress, Uri }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.sse.EventStreamParser
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ BroadcastHub, Keep, Source }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.election.{ ElectionServiceLeaderInfo, LeadershipTransition }
import mesosphere.marathon.core.event.{ AppTerminatedEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.Sink
import org.scalatest.Inside
import scala.concurrent.Future

class EventsControllerTest extends AkkaUnitTest with Inside {
  /* Note - We don't use ScalatestRouteTest because it forces the entire response entity to be consumed before it can be
   * accessed.
   */
  import RequestBuilding._
  val testTerminatedEvent = AppTerminatedEvent(appId = PathId("/my-app"))
  val testSchedulerReregisteredEvent = SchedulerReregisteredEvent("test-mesos-master")
  def withFixture(eventStreamMaxOutstandingMessages: Int = 5)(fn: Fixtures => Unit): Unit = {
    val f = new Fixtures(eventStreamMaxOutstandingMessages)
    try (fn(f)) finally {
      f.leaderStateEventsInput.complete()
    }
  }

  class Fixtures(val eventStreamMaxOutstandingMessages: Int = 5) {
    case class StubLeaderInfo(
        isLeader: Boolean,
        localHostPort: String = "localhost:80") extends ElectionServiceLeaderInfo {
      def leaderHostPort: Option[String] = if (isLeader) Some(localHostPort) else None
    }
    val (leaderStateEventsInput, leaderStateEvents) = Source.queue[LeadershipTransition](16, OverflowStrategy.fail)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run

    implicit val authorizer = mock[Authorizer]
    implicit lazy val authenticator = mock[Authenticator]

    authorizer.isAuthorized(any, any, any) returns true
    authenticator.authenticate(any) returns Future.successful((Some(new Identity {})))

    val eventBus = new EventStream(system)
    val eventsController = new EventsController(
      eventStreamMaxOutstandingMessages = eventStreamMaxOutstandingMessages,
      eventBus = eventBus,
      StubLeaderInfo(isLeader = true),
      leaderStateEvents
    )

    /**
      * Makes a single request. We don't use the akka testkit `check` methods here because they all want to consume the
      * entire response before making any data available.
      *
      * Read [[https://doc.akka.io/docs/akka-http/10.0.10/java/http/routing-dsl/testkit.html#writing-asserting-against-the-httpresponse]]
      */
    def eventsRequest(request: HttpRequest): HttpResponse = {
      Source.single(request)
        .via(Route.handlerFlow(eventsController.route))
        .runWith(Sink.head)
        .futureValue
    }
  }

  "It publishes an event_stream_attached event upon connection for the connected IP" in withFixture() { f =>
    val response = f.eventsRequest(Get().addHeader(`X-Real-Ip`(RemoteAddress(Array[Byte](1, 2, 3, 4)))))

    val attachedEvent = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .runWith(Sink.head)
      .futureValue

    attachedEvent.eventType shouldBe Some("event_stream_attached")
    attachedEvent.data should include("1.2.3.4")
  }

  "Relays events that are published after it is connected" in withFixture() { f =>
    val response = f.eventsRequest(Get())

    val output = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .drop(1)
      .take(1)
      .runWith(Sink.head)

    f.eventBus.publish(testTerminatedEvent)

    val sse = output.futureValue
    sse.eventType shouldBe Some("app_terminated_event")
    sse.data should include("/my-app")
  }

  "Respects filtering conditions" in withFixture() { f =>
    val response = f.eventsRequest(Get(Uri("/?event_type=app_terminated_event")))

    val output = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .runWith(Sink.queue())

    f.eventBus.publish(testSchedulerReregisteredEvent)
    f.eventBus.publish(testTerminatedEvent)
    inside(output.pull().futureValue) {
      case Some(sse) =>
        // It should skip the scheduler registration event, and the original event_stream_attached event
        sse.eventType shouldBe Some("app_terminated_event")
    }
    output.cancel()
  }

  "Closes the stream when leaderhip is lost" in withFixture() { f =>
    val response = f.eventsRequest(Get(Uri("/?filter=app_terminated_event")))

    val events = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .map(_.eventType)
      .runWith(Sink.queue())

    inside(events.pull().futureValue) {
      case Some(Some(tpe)) => tpe shouldBe "event_stream_attached"
    }

    f.leaderStateEventsInput.offer(LeadershipTransition.Standby)
    events.pull().futureValue shouldBe None
  }
}
