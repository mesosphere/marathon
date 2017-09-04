package mesosphere.marathon
package plugin.scheduler

import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.Offer

/**
  * Allows to use external logic to reject offers
  *
  * @author Dmitry Tsydzik.
  * @since 6/15/17.
  */
trait SchedulerPlugin extends Plugin {
  /**
    * @return true if offer is accepted
    */
  def isMatch(offer: Offer, runSpec: RunSpec): Boolean
}
