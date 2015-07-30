package mesosphere.marathon.core.leadership.impl

import akka.actor.{ ActorRef, Props, Actor }
import akka.event.LoggingReceive
import akka.testkit.TestProbe

object ProbeActor {
  def props(testProbe: TestProbe): Props = Props(new ProbeActor(testProbe))

  case class PreStart(self: ActorRef)
  case class PostStop(self: ActorRef)
}

class ProbeActor(testProbe: TestProbe) extends Actor {

  override def preStart(): Unit = {
    testProbe.ref ! ProbeActor.PreStart(self)
  }

  override def postStop(): Unit = {
    testProbe.ref ! ProbeActor.PostStop(self)
  }

  override def receive: Receive = LoggingReceive {
    case any: Any =>
      testProbe.ref.forward(any)
  }
}
