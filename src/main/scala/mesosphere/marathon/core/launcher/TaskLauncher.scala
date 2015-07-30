package mesosphere.marathon.core.launcher

import org.apache.mesos.Protos.{ OfferID, TaskInfo }

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
  def launchTasks(offerID: OfferID, taskInfos: Seq[TaskInfo]): Boolean

  /**
    * Decline the offer. We cannot use the offer afterwards anymore.
    */
  def declineOffer(offerID: OfferID)
}
