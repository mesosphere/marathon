package mesosphere.marathon
package core.event.impl.stream

import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import mesosphere.marathon.core.election.{ ElectionService, LeadershipState }
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamActor._
import mesosphere.marathon.metrics.{ ApiMetric, Metrics, SettableGauge }
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * A HttpEventStreamHandle is a reference to the underlying client http stream.
  */
trait HttpEventStreamHandle {
  def id: String
  def remoteAddress: String
  def sendEvent(event: MarathonEvent): Unit
  def close(): Unit
}

class HttpEventStreamActorMetrics() {
  val numberOfStreams: SettableGauge = Metrics.atomicGauge(ApiMetric, getClass, "number-of-streams")
}

/**
  * This actor handles subscriptions from event stream handler.
  * It subscribes to the event stream and pushes all marathon events to all listener.
  */
class HttpEventStreamActor(
  leaderStateEvents: Source[LeadershipState, Cancellable],
  metrics: HttpEventStreamActorMetrics,
  handleStreamProps: HttpEventStreamHandle => Props)
    extends Actor {
  //map from handle to actor
  private[impl] var streamHandleActors = Map.empty[HttpEventStreamHandle, ActorRef]
  private[this] val log = LoggerFactory.getLogger(getClass)
  private implicit val materializer = ActorMaterializer()
  private var leaderStateSubscriber: Cancellable = _

  override def preStart(): Unit = {
    metrics.numberOfStreams.setValue(0)
    leaderStateSubscriber = leaderStateEvents
      .to(Sink.foreach { state: LeadershipState => self ! state })
      .run
  }

  override def postStop(): Unit = {
    leaderStateSubscriber.cancel()
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
      log.warn("Ignoring open connection request. Closing handle.")
      Try(handle.close())
  }

  /** Accept new connections and create an appropriate handler for them. */
  private[this] def acceptingNewConnections: Receive = {
    case HttpEventStreamConnectionOpen(handle) =>
      metrics.numberOfStreams.setValue(streamHandleActors.size.toLong)
      log.info(s"Add EventStream Handle as event listener: $handle. Current nr of streams: ${streamHandleActors.size}")
      val actor = context.actorOf(handleStreamProps(handle), handle.id)
      context.watch(actor)
      streamHandleActors += handle -> actor
  }

  /** Switch behavior according to leadership changes. */
  private[this] def handleLeadership: Receive = {
    case _: LeadershipState.Standby =>
      log.info("Now standing by. Closing existing handles and rejecting new.")
      context.become(standby)
      streamHandleActors.keys.foreach(removeHandler)

    case LeadershipState.ElectedAsLeader =>
      log.info("Became active. Accepting event streaming requests.")
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
      log.info(s"Removed EventStream Handle as event listener: $handle. " +
        s"Current nr of listeners: ${streamHandleActors.size}")
    }
  }

  private[this] def unexpectedTerminationOfHandlerActor(actor: ActorRef): Unit = {
    streamHandleActors.find(_._2 == actor).foreach {
      case (handle, ref) =>
        log.error(s"Actor terminated unexpectedly: $handle")
        streamHandleActors -= handle
        metrics.numberOfStreams.setValue(streamHandleActors.size.toLong)
    }
  }

  private[this] def warnAboutUnknownMessages: Receive = {
    case message: Any => log.warn(s"Received unexpected message $message")
  }
}

object HttpEventStreamActor {
  case class HttpEventStreamConnectionOpen(handler: HttpEventStreamHandle)
  case class HttpEventStreamConnectionClosed(handle: HttpEventStreamHandle)
}
