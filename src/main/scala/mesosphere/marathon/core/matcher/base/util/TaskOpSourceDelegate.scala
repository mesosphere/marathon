package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.matcher.base.OfferMatcher.TaskOpSource
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.{ TaskOpAccepted, TaskOpRejected }

private class TaskOpSourceDelegate(actorRef: ActorRef) extends TaskOpSource {
  override def taskOpAccepted(taskOp: TaskOp): Unit = actorRef ! TaskOpAccepted(taskOp)
  override def taskOpRejected(taskOp: TaskOp, reason: String): Unit = actorRef ! TaskOpRejected(taskOp, reason)
}

object TaskOpSourceDelegate {
  def apply(actorRef: ActorRef): TaskOpSource = new TaskOpSourceDelegate(actorRef)

  sealed trait TaskOpNotification {
    def taskOp: TaskOp
  }
  object TaskOpNotification {
    def unapply(notification: TaskOpNotification): Option[TaskOp] = Some(notification.taskOp)
  }
  case class TaskOpAccepted(taskOp: TaskOp) extends TaskOpNotification
  case class TaskOpRejected(taskOp: TaskOp, reason: String) extends TaskOpNotification
}
