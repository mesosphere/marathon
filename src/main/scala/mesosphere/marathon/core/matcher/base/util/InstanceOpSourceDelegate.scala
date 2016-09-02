package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.OfferMatcher.TaskOpSource
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.{ TaskOpAccepted, TaskOpRejected }

private class InstanceOpSourceDelegate(actorRef: ActorRef) extends TaskOpSource {
  override def taskOpAccepted(taskOp: InstanceOp): Unit = actorRef ! TaskOpAccepted(taskOp)
  override def taskOpRejected(taskOp: InstanceOp, reason: String): Unit = actorRef ! TaskOpRejected(taskOp, reason)
}

object InstanceOpSourceDelegate {
  def apply(actorRef: ActorRef): TaskOpSource = new InstanceOpSourceDelegate(actorRef)

  sealed trait TaskOpNotification {
    def taskOp: InstanceOp
  }
  object TaskOpNotification {
    def unapply(notification: TaskOpNotification): Option[InstanceOp] = Some(notification.taskOp)
  }
  case class TaskOpAccepted(taskOp: InstanceOp) extends TaskOpNotification
  case class TaskOpRejected(taskOp: InstanceOp, reason: String) extends TaskOpNotification
}
