package mesosphere.marathon.event

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.plugin.event.EventListener

/**
  * Actor, that delegates to all given event listener
  */
class EventDelegate(stream: EventStream, listener: Seq[EventListener]) extends Actor with ActorLogging {

  override def preStart(): Unit = if (listener.nonEmpty) stream.subscribe(self, classOf[MarathonEvent])

  override def receive: Receive = {
    case event: MarathonEvent => listener.foreach(_.handleEvent(event))
  }
}
