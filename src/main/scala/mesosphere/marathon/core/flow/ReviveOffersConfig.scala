package mesosphere.marathon.core.flow

import org.rogach.scallop.ScallopConf

trait ReviveOffersConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val minReviveOffersInterval = opt[Long]("min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(5000))
}
