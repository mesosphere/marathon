package mesosphere.marathon.core.matcher.base.util

import akka.actor.ActorRef
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.OfferMatcher.InstanceOpSource
import mesosphere.marathon.core.matcher.base.util.InstanceOpSourceDelegate.{ InstanceAccepted, InstanceRejected }

private class InstanceOpSourceDelegate(actorRef: ActorRef) extends InstanceOpSource {
  override def instanceOpAccepted(instanceOp: InstanceOp): Unit = actorRef ! InstanceAccepted(instanceOp)
  override def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit =
    actorRef ! InstanceRejected(instanceOp, reason)
}

object InstanceOpSourceDelegate {
  def apply(actorRef: ActorRef): InstanceOpSource = new InstanceOpSourceDelegate(actorRef)

  sealed trait InstanceNotification {
    def instanceOp: InstanceOp
  }
  object InstanceNotification {
    def unapply(notification: InstanceNotification): Option[InstanceOp] = Some(notification.instanceOp)
  }
  case class InstanceAccepted(instanceOp: InstanceOp) extends InstanceNotification
  case class InstanceRejected(instanceOp: InstanceOp, reason: String) extends InstanceNotification
}
