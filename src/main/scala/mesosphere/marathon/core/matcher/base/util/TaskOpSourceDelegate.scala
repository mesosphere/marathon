package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ TaskOp, TaskOpSource }
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.{ TaskLaunchRejected, TaskLaunchAccepted }
import org.apache.mesos.Protos.TaskInfo

private class TaskOpSourceDelegate(actorRef: ActorRef) extends TaskOpSource {
  override def taskOpAccepted(taskOp: TaskOp): Unit = actorRef ! TaskLaunchAccepted(taskOp)
  override def taskOpRejected(taskOp: TaskOp, reason: String): Unit =
    actorRef ! TaskLaunchRejected(taskOp, reason)
}

object TaskOpSourceDelegate {
  def apply(actorRef: ActorRef): TaskOpSource = new TaskOpSourceDelegate(actorRef)

  sealed trait TaskLaunchNotification {
    def taskOp: TaskOp
  }
  object TaskLaunchNotification {
    def unapply(notification: TaskLaunchNotification): Option[TaskOp] = Some(notification.taskOp)
  }
  case class TaskLaunchAccepted(taskOp: TaskOp) extends TaskLaunchNotification
  case class TaskLaunchRejected(taskOp: TaskOp, reason: String) extends TaskLaunchNotification
}
