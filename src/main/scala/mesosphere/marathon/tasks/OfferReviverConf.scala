package mesosphere.marathon.tasks

import org.rogach.scallop.ScallopConf

trait OfferReviverConf extends ScallopConf {
  //scalastyle:off magic.number

  lazy val minReviveOffersInterval = opt[Long]("min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms). (Default: 5000)",
    default = Some(5000))
}
