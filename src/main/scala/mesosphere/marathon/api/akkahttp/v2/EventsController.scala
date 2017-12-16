package mesosphere.marathon
package api.akkahttp.v2

import akka.event.EventStream
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.election.{ ElectionServiceLeaderInfo, LeadershipTransition }
import mesosphere.marathon.core.event.{ EventStreamAttached, EventStreamDetached, MarathonEvent }
import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedResource, Authorizer, ViewResource }
import mesosphere.marathon.stream.{ EnrichedFlow, EnrichedSource }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * The EventsController provides a route to all MarathonEvents published via the event bus.
  */
class EventsController(
    eventStreamMaxOutstandingMessages: Int,
    eventBus: EventStream,
    leaderInfo: ElectionServiceLeaderInfo,
    leaderStateEvents: Source[LeadershipTransition, Any])(
    implicit
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer
) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

  /**
    * GET /v2/events SSE endpoint
    * Query Parameters: event_type*
    * Listen to all MarathonEvents published via the event stream.
    */
  def eventsSSE(): Route = {
    def isAllowed(allowed: Set[String])(event: MarathonEvent): Boolean = allowed.isEmpty || allowed(event.eventType)
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.Events).apply {
        parameters('event_type.*) { events =>
          extractClientIP { clientIp =>
            complete {
              EventsController.eventStreamLogic(eventBus, leaderStateEvents,
                eventStreamMaxOutstandingMessages, clientIp)
                .filter(isAllowed(events.toSet))
                .map(event => ServerSentEvent(`type` = event.eventType, data = Json.stringify(Formats.eventToJson(event))))
                .keepAlive(5.second, () => ServerSentEvent.heartbeat)
            }
          }
        }
      }
    }
  }

  override val route: Route =
    asLeader(leaderInfo) {
      get {
        pathEndOrSingleSlash {
          eventsSSE()
        }
      }
    }
}

object EventsController extends StrictLogging {
  /**
    * An event source which:
    * - Yields all MarathonEvent's for the event bus whilst a leader.
    * - is leader aware. The stream completes if this instance abdicates.
    * - publishes an EventStreamAttached when the stream is materialized
    * - publishes an EventStreamDetached when the stream is completed or fails
    * @param eventStream the event stream to subscribe to
    * @param bufferSize the size of events to buffer, if there is no demand.
    * @param remoteAddress the remote address
    */
  def eventStreamLogic(eventStream: EventStream, leaderEvents: Source[LeadershipTransition, Any], bufferSize: Int, remoteAddress: RemoteAddress) = {

    // Used to propagate a "stream close" signal when we see a LeadershipState.Standy event
    val leaderLossKillSwitch =
      leaderEvents.collect { case evt @ LeadershipTransition.Standby => evt }

    EnrichedSource.eventBusSource(classOf[MarathonEvent], eventStream, bufferSize, OverflowStrategy.fail)
      .via(EnrichedFlow.stopOnFirst(leaderLossKillSwitch))
      .watchTermination()(Keep.both)
      .mapMaterializedValue {
        case (cancellable, completed) =>
          eventStream.publish(EventStreamAttached(remoteAddress = remoteAddress.toString()))
          logger.info(s"EventStream attached: $remoteAddress")

          completed.onComplete { _ =>
            eventStream.publish(EventStreamDetached(remoteAddress = remoteAddress.toString()))
            logger.info(s"EventStream detached: $remoteAddress")
          }(ExecutionContexts.callerThread)
          cancellable
      }
  }
}
