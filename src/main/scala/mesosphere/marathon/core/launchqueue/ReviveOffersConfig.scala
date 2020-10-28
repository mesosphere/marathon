package mesosphere.marathon
package core.launchqueue

import org.rogach.scallop.{ScallopConf, ScallopOption}

trait ReviveOffersConfig extends ScallopConf {

  def mesosRole: ScallopOption[String]

  lazy val minReviveOffersInterval = opt[Long](
    "min_revive_offers_interval",
    descr = "Do not ask for all offers (also already seen ones) more often than this interval (ms).",
    default = Some(5000)
  )

  lazy val suppressOffers = toggle(
    "suppress_offers",
    default = Some(true),
    noshort = true,
    descrYes = "Suppress Mesos offers if Marathon has nothing to launch.",
    descrNo = "(Default) Offers will be continually declined for declineOfferDuration.",
    prefix = "disable_"
  )

  lazy val useOfferConstraints = toggle(
    "mesos_offer_constraints",
    default = Some(false),
    noshort = true,
    descrYes =
      "Send offer constraints to Mesos to reduce the number of offers Marathon needs to decline due to placement constraints (experimental).",
    descrNo = "(Default) Mesos will send unconstrained offers to Marathon.",
    prefix = "disable_"
  )

  lazy val minOfferConstraintsUpdateInterval = opt[Long](
    "min_mesos_offer_constraints_update_interval",
    descr = "Avoid updating Mesos offer constraints more often than this interval (ms). Has no effect without `--mesos_offer_constraints`.",
    default = Some(1000)
  )
}
