package mesosphere.marathon.event.http

import java.io.EOFException

import akka.actor.{ Actor, ActorLogging, Status }
import akka.event.EventStream
import akka.pattern.pipe
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.event.http.HttpEventStreamHandleActor.WorkDone
import mesosphere.marathon.event.{ EventStreamAttached, EventStreamDetached, MarathonEvent }
import mesosphere.util.ThreadPoolContext
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.Try

class HttpEventStreamHandleActor(
    handle: HttpEventStreamHandle,
    stream: EventStream,
    maxOutStanding: Int) extends Actor with ActorLogging {

  private[http] var outstanding = List.empty[MarathonEvent]

  override def preStart(): Unit = {
    stream.subscribe(self, classOf[MarathonEvent])
    stream.publish(EventStreamAttached(handle.remoteAddress))
  }

  override def postStop(): Unit = {
    log.info(s"Stop actor $handle")
    stream.unsubscribe(self)
    stream.publish(EventStreamDetached(handle.remoteAddress))
    Try(handle.close()) //ignore, if this fails
  }

  override def receive: Receive = waitForEvent

  def waitForEvent: Receive = {
    case event: MarathonEvent =>
      outstanding = event :: outstanding
      sendAllMessages()
  }

  def stashEvents: Receive = handleWorkDone orElse {
    case event: MarathonEvent if outstanding.size >= maxOutStanding => dropEvent(event)
    case event: MarathonEvent                                       => outstanding = event :: outstanding
  }

  def handleWorkDone: Receive = {
    case WorkDone => sendAllMessages()
    case Status.Failure(ex) =>
      handleException(ex)
      sendAllMessages()
  }

  private[this] def sendAllMessages(): Unit = {
    if (outstanding.nonEmpty) {
      val toSend = outstanding.reverse
      outstanding = List.empty[MarathonEvent]
      context.become(stashEvents)
      val sendFuture = Future {
        toSend.foreach(event => handle.sendEvent(event.eventType, Json.stringify(eventToJson(event))))
        WorkDone
      }(ThreadPoolContext.ioContext)

      import context.dispatcher
      sendFuture pipeTo self
    }
    else {
      context.become(waitForEvent)
    }
  }

  private[this] def handleException(ex: Throwable): Unit = ex match {
    case eof: EOFException =>
      log.info(s"Received EOF from stream handle $handle. Ignore subsequent events.")
      //We know the connection is dead, but it is not finalized from the container.
      //Do not act any longer on any event.
      context.become(Actor.emptyBehavior)
    case _ =>
      log.warning("Could not send message to {} reason: {}", handle, ex)
  }

  private[this] def dropEvent(event: MarathonEvent): Unit = {
    log.warning("Ignore event {} for handle {} (slow consumer)", event, handle)
  }
}

object HttpEventStreamHandleActor {
  object WorkDone
}

