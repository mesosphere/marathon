package mesosphere.marathon
package core.leadership.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.leadership.PreparationMessages.PrepareForStart
import mesosphere.marathon.core.leadership.impl.WhenLeaderActor.Stop

import scala.concurrent.Future
import scala.concurrent.duration._

private[leadership] class LeadershipCoordinatorDelegate(actorRef: ActorRef) extends LeadershipCoordinator {
  override def prepareForStart(): Future[Unit] = {
    import mesosphere.marathon.core.async.ExecutionContexts.global
    implicit val timeout: Timeout = 10.seconds
    (actorRef ? PrepareForStart).map(_ => ())
  }

  override def stop(): Unit = actorRef ! Stop
}
