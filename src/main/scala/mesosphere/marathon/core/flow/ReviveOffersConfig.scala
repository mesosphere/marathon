package mesosphere.marathon.core.flow

import org.rogach.scallop.ScallopConf

trait ReviveOffersConfig extends ScallopConf {
  //scalastyle:off magic.number

  /**
    * Separate disable option for --revive_offers_for_new_apps since there doesn't seem
    * to be a syntax to disable a flag in Scallop.
    */
  lazy val disableReviveOffersForNewApps = opt[Boolean]("disable_revive_offers_for_new_apps",
    descr = "Disable reviveOffers for new or changed apps. (Default: use reviveOffers) ",
    default = Some(false))

  lazy val reviveOffersForNewApps = opt[Boolean]("revive_offers_for_new_apps",
    descr = "Whether to call reviveOffers for new or changed apps. " +
      "(Default: use reviveOffers, disable with --disable_revive_offers_for_new_apps) ",
    hidden = true,
    default = Some(true))

  lazy val shouldReviveOffersForNewApps = !disableReviveOffersForNewApps() && reviveOffersForNewApps()

  lazy val minReviveOffersInterval = opt[Long]("min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(5000))
}
