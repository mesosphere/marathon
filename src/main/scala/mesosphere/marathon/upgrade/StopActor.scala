package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, ActorRef, Terminated }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel

import scala.concurrent.Promise

class StopActor(toStop: ActorRef, promise: Promise[Boolean], reason: Throwable) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    context.watch(toStop)
    toStop ! Cancel(reason)
  }

  def receive: Receive = {
    case Terminated(`toStop`) =>
      promise.success(true)
      log.error(s"$toStop has successfully been stopped. reason: ${reason.getMessage}")
      context.unwatch(toStop)
      context.stop(self)
  }
}
