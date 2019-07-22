package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.{ScallopConf, ScallopOption}

trait ReviveOffersConfig extends ScallopConf {

  def mesosRole: ScallopOption[String]

  // TODO: deprecate this
  lazy val reviveOffersForNewApps = toggle(
    "revive_offers_for_new_apps",
    descrYes = "(Default) Call reviveOffers for new or changed apps.",
    descrNo = "Disable reviveOffers for new or changed apps.",
    hidden = true,
    default = Some(true),
    prefix = "disable_")

  lazy val minReviveOffersInterval = opt[Long](
    "min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(5000))

  /**
    * Deprecated. Has no effect
    */
  lazy val reviveOffersRepetitions = opt[Int](
    "revive_offers_repetitions",
    descr = "Deprecated parameter; no longer has any effect",
    default = Some(3))

  lazy val suppressOffers = toggle(
    "suppress_offers",
    default = Some(true),
    noshort = true,
    descrYes = "Suppress Mesos offers if Marathon has nothing to launch.",
    descrNo = "(Default) Offers will be continually declined for declineOfferDuration.",
    prefix = "disable_"
  )
}
