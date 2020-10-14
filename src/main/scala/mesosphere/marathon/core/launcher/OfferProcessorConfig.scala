package mesosphere.marathon
package core.launcher

import org.rogach.scallop.ScallopConf

trait OfferProcessorConfig extends ScallopConf {

  lazy val declineOfferDuration = opt[Long](
    "decline_offer_duration",
    descr = "(Default: 120 seconds) " +
      "The duration (milliseconds) for which to decline offers by default",
    default = Some(120000)
  )
}
