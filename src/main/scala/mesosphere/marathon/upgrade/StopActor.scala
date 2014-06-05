package mesosphere.marathon.upgrade

import akka.actor.{ActorLogging, Terminated, Actor, ActorRef}
import scala.concurrent.Promise
import mesosphere.marathon.upgrade.AppUpgradeActor.Cancel

class StopActor(toStop: ActorRef, promise: Promise[Boolean], reason: Throwable) extends Actor with ActorLogging {

  override def preStart() = {
    context.watch(toStop)
    toStop ! Cancel(reason)
  }

  def receive = {
    case Terminated(`toStop`) =>
      promise.success(true)
      log.debug(s"$toStop has successfully been stopped.")
      context.unwatch(toStop)
      context.stop(self)
  }
}
