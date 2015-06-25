package mesosphere.marathon

import akka.actor.{ Actor, ActorRef, Props }

/**
  * An actor which forwards all its messages
  * to the given destination. Useful for testing.
  */
class ForwardingActor(destination: ActorRef) extends Actor {
  override def receive: Receive = {
    case msg => destination.forward(msg)
  }
}

object ForwardingActor {
  def props(destination: ActorRef): Props = {
    Props(classOf[ForwardingActor], destination)
  }
}
