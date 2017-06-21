package mesosphere.marathon
package core.event.impl.stream

import java.io.EOFException

import akka.actor.{ Actor, Status }
import akka.event.EventStream
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.impl.stream.HttpEventStreamHandleActor._
import mesosphere.marathon.core.event.{ EventStreamAttached, EventStreamDetached, MarathonEvent }
import mesosphere.util.ThreadPoolContext

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

class HttpEventStreamHandleActor(
    handle: HttpEventStreamHandle,
    stream: EventStream,
    maxOutStanding: Int) extends Actor with StrictLogging {

  private[impl] var outstanding = Seq.empty[MarathonEvent]

  override def preStart(): Unit = {
    stream.subscribe(self, classOf[MarathonEvent])
    stream.publish(EventStreamAttached(handle.remoteAddress))
  }

  override def postStop(): Unit = {
    logger.info(s"Stop actor $handle")
    stream.unsubscribe(self)
    stream.publish(EventStreamDetached(handle.remoteAddress))
    Try(handle.close()) //ignore, if this fails
  }

  override def receive: Receive = waitForEvent

  def waitForEvent: Receive = {
    case event: MarathonEvent =>
      outstanding = event +: outstanding
      sendAllMessages()
  }

  def stashEvents: Receive = handleWorkDone orElse {
    case event: MarathonEvent if outstanding.size >= maxOutStanding => dropEvent(event)
    case event: MarathonEvent => outstanding = event +: outstanding
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
        toSend.foreach(event => handle.sendEvent(event))
        WorkDone
      }(ThreadPoolContext.ioContext)

      import context.dispatcher
      sendFuture pipeTo self
    } else {
      context.become(waitForEvent)
    }
  }

  private[this] def handleException(ex: Throwable): Unit = ex match {
    case eof: EOFException =>
      logger.info(s"Received EOF from stream handle $handle. Ignore subsequent events.")
      //We know the connection is dead, but it is not finalized from the container.
      //Do not act any longer on any event.
      context.become(Actor.emptyBehavior)
    case NonFatal(e) =>
      logger.warn(s"Could not send message to $handle reason:", e)
  }

  private[this] def dropEvent(event: MarathonEvent): Unit = {
    logger.warn(s"Ignore event $event for handle $handle (slow consumer)")
  }
}

object HttpEventStreamHandleActor {
  object WorkDone
}

