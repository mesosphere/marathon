package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.TaskLaunchSource
import mesosphere.marathon.core.matcher.base.util.TaskLaunchSourceDelegate.{ TaskLaunchRejected, TaskLaunchAccepted }
import org.apache.mesos.Protos.TaskInfo

private class TaskLaunchSourceDelegate(actorRef: ActorRef) extends TaskLaunchSource {
  override def taskLaunchAccepted(taskInfo: TaskInfo): Unit = actorRef ! TaskLaunchAccepted(taskInfo)
  override def taskLaunchRejected(taskInfo: TaskInfo, reason: String): Unit =
    actorRef ! TaskLaunchRejected(taskInfo, reason)
}

object TaskLaunchSourceDelegate {
  def apply(actorRef: ActorRef): TaskLaunchSource = new TaskLaunchSourceDelegate(actorRef)

  sealed trait TaskLaunchNotification {
    def taskInfo: TaskInfo
  }
  object TaskLaunchNotification {
    def unapply(notification: TaskLaunchNotification): Option[TaskInfo] = Some(notification.taskInfo)
  }
  case class TaskLaunchAccepted(taskInfo: TaskInfo) extends TaskLaunchNotification
  case class TaskLaunchRejected(taskInfo: TaskInfo, reason: String) extends TaskLaunchNotification
}
