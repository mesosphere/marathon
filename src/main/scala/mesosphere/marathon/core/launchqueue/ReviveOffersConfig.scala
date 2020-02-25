package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.{ScallopConf, ScallopOption}

trait ReviveOffersConfig extends ScallopConf {

  def mesosRole: ScallopOption[String]

  lazy val minReviveOffersInterval = opt[Long](
    "min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(5000))

  lazy val suppressOffers = toggle(
    "suppress_offers",
    default = Some(true),
    noshort = true,
    descrYes = "Suppress Mesos offers if Marathon has nothing to launch.",
    descrNo = "(Default) Offers will be continually declined for declineOfferDuration.",
    prefix = "disable_"
  )
}
