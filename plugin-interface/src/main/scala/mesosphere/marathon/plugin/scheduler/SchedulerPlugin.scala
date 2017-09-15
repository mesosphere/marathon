package mesosphere.marathon
package plugin.scheduler

import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.Offer

/**
  * Allows to use external logic to decline offers.
  */
trait SchedulerPlugin extends Plugin {

  /**
    * @return true if offer matches
    */
  def isMatch(offer: Offer, runSpec: RunSpec): Boolean
}
