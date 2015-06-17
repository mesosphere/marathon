package mesosphere.marathon.core.launcher

import org.rogach.scallop.ScallopConf

trait OfferProcessorConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val offerMatchingTimeout = opt[Int]("offer_matching_timeout",
    descr = "Offer matching timeout (ms). Stop trying to match additional tasks for this offer after this time.",
    default = Some(1000))
}
