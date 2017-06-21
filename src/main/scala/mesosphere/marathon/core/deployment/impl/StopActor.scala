package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.{ Actor, ActorRef, Terminated }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.impl.DeploymentActor.Cancel

import scala.concurrent.Promise

class StopActor(toStop: ActorRef, promise: Promise[Done], reason: Throwable) extends Actor with StrictLogging {

  override def preStart(): Unit = {
    context.watch(toStop)
    toStop ! Cancel(reason)
  }

  def receive: Receive = {
    case Terminated(`toStop`) =>
      promise.success(Done)
      logger.debug(s"$toStop has successfully been stopped.")
      context.unwatch(toStop)
      context.stop(self)
  }
}
