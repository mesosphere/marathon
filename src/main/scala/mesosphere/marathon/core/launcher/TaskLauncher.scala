package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.matcher.base.OfferMatcher.TaskOp
import org.apache.mesos.Protos.OfferID

/**
  * A TaskLauncher launches tasks on an offer or declines an offer.
  */
trait TaskLauncher {
  /**
    * Launch the given tasks on the given offer. The offer is consumed afterwards and
    * cannot be used anymore.
    *
    * @return `true` if we could communicate the task launch to Mesos and `false` otherwise
    */
  def acceptOffer(offerID: OfferID, taskOps: Seq[TaskOp]): Boolean

  /**
    * Decline the offer. We cannot use the offer afterwards anymore.
    */
  def declineOffer(offerID: OfferID, refuseMilliseconds: Option[Long])
}
