package mesosphere.marathon.event.http

import java.io.EOFException

import akka.actor.{ Actor, Status }
import akka.event.EventStream
import akka.pattern.pipe
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.event.http.HttpEventStreamHandleActor.WorkDone
import mesosphere.marathon.event.{ EventStreamAttached, EventStreamDetached, MarathonEvent }
import mesosphere.util.ThreadPoolContext
import org.apache.log4j.Logger
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.Try

class HttpEventStreamHandleActor(
    handle: HttpEventStreamHandle,
    stream: EventStream,
    maxOutStanding: Int) extends Actor {

  private[this] val log = Logger.getLogger(getClass.getName)
  private[http] var outstanding = 0 //the number of unhandled outstanding send event futures

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

  def handleException(ex: Throwable): Unit = ex match {
    case eof: EOFException =>
      log.info(s"Received EOF from stream handle $handle. Ignore subsequent events.")
      //We know the connection is dead, but it is not finalized from the container.
      //Do not act any longer on any event.
      context.become(Actor.emptyBehavior)
    case _ =>
      log.warn(s"Could not send message to $handle", ex)
  }

  override def receive: Receive = sendEvents

  def sendEvents: Receive = {
    case event: MarathonEvent if outstanding < maxOutStanding =>
      implicit val ec = ThreadPoolContext.context
      Future {
        handle.sendMessage(Json.stringify(eventToJson(event)))
        WorkDone
      } pipeTo self
      outstanding += 1
    case event: MarathonEvent =>
      log.warn(s"Ignore event $event for handle $handle (slow consumer)")
      context.become(dropEvents)
    case Status.Failure(ex) =>
      outstanding -= 1
      handleException(ex)
    case WorkDone =>
      outstanding -= 1
  }

  def dropEvents: Receive = {
    case event: MarathonEvent =>
      log.warn(s"Ignore event $event for handle $handle (slow consumer)")
    case Status.Failure(ex) =>
      outstanding -= 1
      handleException(ex)
      if (outstanding < maxOutStanding) context.become(sendEvents)
    case WorkDone =>
      outstanding -= 1
      if (outstanding < maxOutStanding) context.become(sendEvents)
  }
}

object HttpEventStreamHandleActor {
  object WorkDone
}

