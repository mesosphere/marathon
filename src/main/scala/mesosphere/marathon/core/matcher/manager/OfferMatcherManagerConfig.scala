package mesosphere.marathon.core.matcher.manager

import org.rogach.scallop.ScallopConf

trait OfferMatcherManagerConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val maxTasksPerOffer = opt[Int]("max_tasks_per_offer",
    descr = "Maximum tasks per offer. Do not start more than this number of tasks on a single offer.",
    default = Some(5))

  lazy val maxTasksPerOfferCycle = opt[Int]("max_tasks_per_offer_cycle",
    descr = "DEPRECATED. NO EFFECT. Maximally launch this number of tasks per offer cycle.",
    default = Some(1000),
    hidden = true,
    noshort = true)

}
