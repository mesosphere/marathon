package mesosphere.marathon
package core.launcher

import org.rogach.scallop.ScallopConf

trait OfferProcessorConfig extends ScallopConf {

  @deprecated("The save tasks timeout is not used any longer", since = "1.5")
  lazy val saveTasksToLaunchTimeout = opt[Int](
    "save_tasks_to_launch_timeout",
    descr = "Timeout (ms) after matching an offer for saving all matched tasks that we are about to launch. " +
      "When reaching the timeout, only the tasks that we could save within the timeout are also launched. " +
      "All other task launches are temporarily rejected and retried later.",
    default = Some(3000),
    hidden = true
  )

  lazy val declineOfferDuration = opt[Long](
    "decline_offer_duration",
    descr = "(Default: 120 seconds) " +
      "The duration (milliseconds) for which to decline offers by default",
    default = Some(120000))
}
