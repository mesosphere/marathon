package mesosphere.marathon
package core.event.impl.stream

import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.Formats.eventToJson
import mesosphere.marathon.core.election.{ ElectionService, LeadershipTransition }
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor._
import mesosphere.marathon.metrics.{ ApiMetric, Metrics, SettableGauge }
import play.api.libs.json.Json

import scala.util.Try

/**
  * A HttpEventStreamHandle is a reference to the underlying client http stream.
  */
trait HttpEventStreamHandle {
  def id: String
  def remoteAddress: String
  def sendEvent(event: SerializedMarathonEvent): Unit
  def close(): Unit
  val useLightWeightEvents: Boolean
}

class HttpEventStreamActorMetrics() {
  val numberOfStreams: SettableGauge = Metrics.atomicGauge(ApiMetric, getClass, "number-of-streams")
}

/**
  * This actor handles subscriptions from event stream handler.
  * It subscribes to the event stream and pushes all marathon events to all listener.
  */
class HttpEventStreamActor(
    electionService: ElectionService,
    stream: EventStream,
    metrics: HttpEventStreamActorMetrics,
    handleStreamProps: HttpEventStreamHandle => Props)
  extends Actor with StrictLogging {
  //map from handle to actor
  private[impl] var streamHandleActors = Map.empty[HttpEventStreamHandle, ActorRef]

  override def preStart(): Unit = {
    metrics.numberOfStreams.setValue(0)
    electionService.subscribe(self)
    stream.subscribe(self, classOf[MarathonEvent])
  }

  override def postStop(): Unit = {
    electionService.unsubscribe(self)
    stream.unsubscribe(self)
    metrics.numberOfStreams.setValue(0)
  }

  override def receive: Receive = standby

  // behaviours
  private[this] val active: Receive = behaviour(acceptingNewConnections)
  private[this] val standby: Receive = behaviour(rejectingNewConnections)

  /**
    * Helper method to create behaviours.
    * The behaviours only differ in how they deal with new connections.
    */
  private[this] def behaviour(newConnectionBehaviour: Receive): Receive = {
    Seq(
      handleLeadership,
      cleanupHandlerActors,
      newConnectionBehaviour,
      handleEvents,
      warnAboutUnknownMessages
    ).reduceLeft {
      // Prevent fatal warning about deriving type Any as type parameter
      _.orElse[Any, Unit](_)
    }
  }

  // behaviour components

  /** Immediately close new connections. */
  private[this] def rejectingNewConnections: Receive = {
    case HttpEventStreamConnectionOpen(handle) =>
      logger.warn("Ignoring open connection request. Closing handle.")
      Try(handle.close())
  }

  /** Accept new connections and create an appropriate handler for them. */
  private[this] def acceptingNewConnections: Receive = {
    case HttpEventStreamConnectionOpen(handle) =>
      metrics.numberOfStreams.setValue(streamHandleActors.size.toLong)
      logger.info(s"Add EventStream Handle as event listener: $handle. Current nr of streams: ${streamHandleActors.size}")
      val actor = context.actorOf(handleStreamProps(handle), handle.id)
      context.watch(actor)
      streamHandleActors += handle -> actor
  }

  /** Switch behavior according to leadership changes. */
  private[this] def handleLeadership: Receive = {
    case LeadershipTransition.Standby =>
      logger.info("Now standing by. Closing existing handles and rejecting new.")
      context.become(standby)
      streamHandleActors.keys.foreach(removeHandler)

    case LeadershipTransition.ElectedAsLeaderAndReady =>
      logger.info("Became active. Accepting event streaming requests.")
      context.become(active)
  }

  /** Cleanup child actors which are not needed anymore. */
  private[this] def cleanupHandlerActors: Receive = {
    case HttpEventStreamConnectionClosed(handle) => removeHandler(handle)
    case Terminated(actor) => unexpectedTerminationOfHandlerActor(actor)
  }

  private[this] def removeHandler(handle: HttpEventStreamHandle): Unit = {
    streamHandleActors.get(handle).foreach { actor =>
      context.unwatch(actor)
      context.stop(actor)
      streamHandleActors -= handle
      metrics.numberOfStreams.setValue(streamHandleActors.size.toLong)
      logger.info(s"Removed EventStream Handle as event listener: $handle. " +
        s"Current nr of listeners: ${streamHandleActors.size}")
    }
  }

  private[this] def unexpectedTerminationOfHandlerActor(actor: ActorRef): Unit = {
    streamHandleActors.find(_._2 == actor).foreach {
      case (handle, ref) =>
        logger.error(s"Actor terminated unexpectedly: $handle")
        streamHandleActors -= handle
        metrics.numberOfStreams.setValue(streamHandleActors.size.toLong)
    }
  }

  def handleEvents: Receive = {
    case event: MarathonEvent =>
      // Broadcast events to all handle actors.
      val fullEvent = {
        val payload = Json.stringify(eventToJson(event, false))
        SerializedMarathonEvent(event.eventType, payload)
      }
      val lightEvent = {
        val payload = Json.stringify(eventToJson(event, true))
        SerializedMarathonEvent(event.eventType, payload)
      }
      streamHandleActors.foreach {
        case (handle, actor) =>
          if (handle.useLightWeightEvents) actor ! lightEvent
          else actor ! fullEvent
      }
  }

  private[this] def warnAboutUnknownMessages: Receive = {
    case message: Any => logger.warn(s"Received unexpected message $message")
  }
}

object HttpEventStreamActor {
  case class HttpEventStreamConnectionOpen(handler: HttpEventStreamHandle)
  case class HttpEventStreamConnectionClosed(handle: HttpEventStreamHandle)

  case class SerializedMarathonEvent(eventType: String, payload: String)
}
