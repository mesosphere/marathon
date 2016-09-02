package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.OfferMatcher.InstanceOpSource
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.{ InstanceOpAccepted, InstanceOpRejected }

private class InstanceOpSourceDelegate(actorRef: ActorRef) extends InstanceOpSource {
  override def instanceOpAccepted(instanceOp: InstanceOp): Unit = actorRef ! InstanceOpAccepted(instanceOp)
  override def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit =
    actorRef ! InstanceOpRejected(instanceOp, reason)
}

object InstanceOpSourceDelegate {
  def apply(actorRef: ActorRef): InstanceOpSource = new InstanceOpSourceDelegate(actorRef)

  sealed trait InstanceOpNotification {
    def instanceOp: InstanceOp
  }
  object InstanceOpNotification {
    def unapply(notification: InstanceOpNotification): Option[InstanceOp] = Some(notification.instanceOp)
  }
  case class InstanceOpAccepted(instanceOp: InstanceOp) extends InstanceOpNotification
  case class InstanceOpRejected(instanceOp: InstanceOp, reason: String) extends InstanceOpNotification
}
