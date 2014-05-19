package mesosphere.marathon.upgrade

import akka.actor.{Terminated, Actor, ActorRef}
import scala.concurrent.Promise

class StopActor(toStop: ActorRef, promise: Promise[Boolean]) extends Actor {

  override def preStart() = {
    context.watch(toStop)
    context.stop(toStop)
  }

  def receive = {
    case Terminated(`toStop`) =>
      promise.success(true)
      context.unwatch(toStop)
      context.stop(self)
  }
}
