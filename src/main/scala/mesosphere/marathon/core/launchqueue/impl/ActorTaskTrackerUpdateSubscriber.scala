package mesosphere.marathon.core.launchqueue.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.task.{ Task, TaskStateChange }
import mesosphere.marathon.core.task.tracker.TaskTrackerUpdateSubscriber

import scala.concurrent.Future
import scala.concurrent.duration._

class ActorTaskTrackerUpdateSubscriber(actorRef: ActorRef) extends TaskTrackerUpdateSubscriber {

  override def handleTaskStateChange(stateChange: TaskStateChange): Future[Unit] = {
    implicit val timeout: Timeout = Timeout(1.second)
    val answerFuture = actorRef ? ActorTaskTrackerUpdateSubscriber.HandleTaskStateChange(stateChange)
    answerFuture.mapTo[Unit]
  }

  override def toString: String = s"ActorTaskUpdateSubscriber($actorRef)"
}

object ActorTaskTrackerUpdateSubscriber {
  case class HandleTaskStateChange(stateChange: TaskStateChange)
}
