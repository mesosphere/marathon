package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.TaskOpSource
import mesosphere.marathon.core.matcher.base.util.TaskOpSourceDelegate.{ TaskLaunchRejected, TaskLaunchAccepted }
import org.apache.mesos.Protos.TaskInfo

private class TaskOpSourceDelegate(actorRef: ActorRef) extends TaskOpSource {
  override def taskOpAccepted(taskInfo: TaskInfo): Unit = actorRef ! TaskLaunchAccepted(taskInfo)
  override def taskOpRejected(taskInfo: TaskInfo, reason: String): Unit =
    actorRef ! TaskLaunchRejected(taskInfo, reason)
}

object TaskOpSourceDelegate {
  def apply(actorRef: ActorRef): TaskOpSource = new TaskOpSourceDelegate(actorRef)

  sealed trait TaskLaunchNotification {
    def taskInfo: TaskInfo
  }
  object TaskLaunchNotification {
    def unapply(notification: TaskLaunchNotification): Option[TaskInfo] = Some(notification.taskInfo)
  }
  case class TaskLaunchAccepted(taskInfo: TaskInfo) extends TaskLaunchNotification
  case class TaskLaunchRejected(taskInfo: TaskInfo, reason: String) extends TaskLaunchNotification
}
