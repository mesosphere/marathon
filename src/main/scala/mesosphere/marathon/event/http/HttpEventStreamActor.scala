package mesosphere.marathon.event.http

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.event.http.HttpEventStreamActor._
import org.apache.log4j.Logger
import play.api.libs.json.Json

/**
  * A HttpEventStreamHandle is a reference to the underlying client http stream.
  */
trait HttpEventStreamHandle {
  def id: String
  def remoteAddress: String
  def sendMessage(message: String): Unit
  def close(): Unit
}

/**
  * This actor handles subscriptions from event stream handler.
  * It subscribes to the event stream and pushes all marathon events to all listener.
  * @param eventStream the marathon event stream
  */
class HttpEventStreamActor(eventStream: EventStream, maxOutstandingMessages: Int) extends Actor {
  //map from handle to actor
  private[http] var clients = Map.empty[HttpEventStreamHandle, ActorRef]
  private[this] val log = Logger.getLogger(getClass)

  override def receive: Receive = {
    case HttpEventStreamConnectionOpen(handle)           => addHandler(handle)
    case HttpEventStreamConnectionClosed(handle)         => removeHandler(handle)
    case HttpEventStreamIncomingMessage(handle, message) => sendReply(handle, message)
    case Terminated(actor)                               => removeActor(actor)
  }

  def addHandler(handle: HttpEventStreamHandle): Unit = {
    log.info(s"Add EventStream Handle as event listener: $handle. Current nr of listeners: ${clients.size}")
    val actor = context.actorOf(Props(
      classOf[HttpEventStreamHandleActor],
      handle,
      eventStream,
      maxOutstandingMessages), handle.id)
    context.watch(actor)
    clients += handle -> actor
  }

  def removeHandler(handle: HttpEventStreamHandle): Unit = {
    clients.get(handle).foreach { actor =>
      context.unwatch(actor)
      context.stop(actor)
      clients -= handle
      log.info(s"Removed EventStream Handle as event listener: $handle. Current nr of listeners: ${clients.size}")
    }
  }

  def removeActor(actor: ActorRef): Unit = {
    clients.find(_._2 == actor).foreach {
      case (handle, ref) =>
        log.error(s"Actor terminated unexpectedly: $handle")
        clients -= handle
    }
  }

  def sendReply(handle: HttpEventStreamHandle, message: String): Unit = {
    handle.sendMessage(Json.stringify(Json.obj("received" -> message)))
  }
}

object HttpEventStreamActor {
  case class HttpEventStreamConnectionOpen(handler: HttpEventStreamHandle)
  case class HttpEventStreamConnectionClosed(handle: HttpEventStreamHandle)
  case class HttpEventStreamIncomingMessage(handle: HttpEventStreamHandle, message: String)
}
