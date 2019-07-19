package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.{ScallopConf, ScallopOption}

trait ReviveOffersConfig extends ScallopConf {

  def mesosRole: ScallopOption[String]

  /**
    * Deprecated. Has no effect
    */
  lazy val reviveOffersForNewApps = {
    require(BuildInfo.version.minor == 9, "This option must be removed in Marathon 1.10")
    toggle(
      "revive_offers_for_new_apps",
      descrYes = "Deprecated, has no effect",
      descrNo = "Deprecated, has no effect",
      hidden = true,
      default = Some(true),
      prefix = "disable_")
  }

  lazy val minReviveOffersInterval = opt[Long](
    "min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(30000))

  /**
    * Deprecated. Has no effect
    */
  lazy val reviveOffersRepetitions = {
    require(BuildInfo.version.minor == 9, "This option must be removed in Marathon 1.10")

    opt[Int](
      "revive_offers_repetitions",
      descr = "Deprecated parameter; no longer has any effect",
      default = Some(3))
  }

  lazy val suppressOffers = toggle(
    "suppress_offers",
    default = Some(true),
    noshort = true,
    descrYes = "Suppress Mesos offers if Marathon has nothing to launch.",
    descrNo = "(Default) Offers will be continually declined for declineOfferDuration.",
    prefix = "disable_"
  )
}
