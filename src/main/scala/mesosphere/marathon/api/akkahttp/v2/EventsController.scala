package mesosphere.marathon
package api.akkahttp.v2

import akka.NotUsed
import akka.actor.Actor.Receive
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.server.Route
import akka.stream._
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkasse.ServerSentEvent
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.v2.EventsController.EventStreamSourceGraph
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event.{ EventConf, EventStreamAttached, EventStreamDetached, MarathonEvent }
import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedResource, Authorizer, ViewResource }
import play.api.libs.json.Json

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * The EventsController provides a route to all MarathonEvents published via the event bus.
  */
class EventsController(
    val conf: EventConf,
    val eventBus: EventStream)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import de.heikoseeberger.akkasse.EventStreamMarshalling._

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
              Source
                .fromGraph[MarathonEvent, NotUsed](
                  new EventStreamSourceGraph(eventBus, conf.eventStreamMaxOutstandingMessages(), clientIp)
                )
                .filter(isAllowed(events.toSet))
                .map(event => ServerSentEvent(`type` = event.eventType, data = Json.stringify(Formats.eventToJson(event))))
                .keepAlive(5.second, () => ServerSentEvent.heartbeat)
            }
          }
        }
      }
    }
  }

  override val route: Route = {
    asLeader(electionService) {
      get {
        pathEnd {
          eventsSSE()
        }
      }
    }
  }
}

object EventsController extends StrictLogging {

  /**
    * Represents a graph stage that implements a Source[MarathonEvent] which:
    * - registers to the given EventStream and pushes all elements downstream.
    * - buffers maxMessages elements. The stream fails, if the buffer is full.
    * - is leader aware. The stream completes, if this instance abdicates.
    * - publishes an EventStreamAttached, if the stream is materialized
    * - publishes an EventStreamDetached, if the stream is completed or failed.
    * @param eventStream the event stream to subscribe to
    * @param bufferSize the size of events to buffer, if there is no demand.
    * @param remoteAddress the remote address
    */
  class EventStreamSourceGraph(eventStream: EventStream, bufferSize: Int, remoteAddress: RemoteAddress) extends GraphStage[SourceShape[MarathonEvent]] with StrictLogging {
    private[this] val out: Outlet[MarathonEvent] = Outlet("EventsController.EventPublisher.out")
    override val shape: SourceShape[MarathonEvent] = SourceShape.of(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      val messages: mutable.Queue[MarathonEvent] = mutable.Queue.empty
      var actorRef: Option[StageActor] = None

      setHandler(out, new OutHandler {
        override def onPull(): Unit = if (messages.nonEmpty) push(out, messages.dequeue())
      })

      override def preStart(): Unit = {
        super.preStart()
        val actor = getStageActor(receive)
        eventStream.subscribe(actor.ref, classOf[MarathonEvent])
        eventStream.subscribe(actor.ref, classOf[LocalLeadershipEvent])
        eventStream.publish(EventStreamAttached(remoteAddress = remoteAddress.toString()))
        logger.info(s"EventStream attached: $remoteAddress")
        actorRef = Some(actor)
      }

      override def postStop(): Unit = {
        actorRef.foreach(actor => eventStream.unsubscribe(actor.ref))
        eventStream.publish(EventStreamDetached(remoteAddress = remoteAddress.toString()))
        logger.info(s"EventStream detached: $remoteAddress")
        super.postStop()
      }

      def receive: Receive = {
        case (_, LocalLeadershipEvent.Standby) => completeStage()
        case (_, LocalLeadershipEvent.ElectedAsLeader) => //ignore
        case (_, _: MarathonEvent) if messages.size > bufferSize =>
          logger.warn(s"Buffer is full - slow receiver. Close connection: $remoteAddress")
          failStage(BufferOverflowException(s"Buffer overflow (max capacity was: $bufferSize)!"))
        case (_, event: MarathonEvent) =>
          messages.enqueue(event)
          while (isAvailable(out) && messages.nonEmpty) push(out, messages.dequeue())
      }
    }
  }
}
