package mesosphere.marathon
package core.matcher.manager

import org.rogach.scallop.ScallopConf

import scala.concurrent.duration._

trait OfferMatcherManagerConfig extends ScallopConf {

  /**
    * The time an offer is allowed to stay in the matching pipeline until it is matched and processed.
    * After the timeout is reached, the offer will not be matched any longer but all collected results will be processed.
    */
  lazy val offerMatchingTimeout = opt[Int](
    "offer_matching_timeout",
    descr = "Offer matching timeout (ms). Stop trying to match additional tasks for this offer after this time.",
    default = Some(3000)).map(_.millis)

  /**
    * Do not start more instances on one offer than this number.
    * This will prevent to overload mesos agents by spawning too many tasks at the same time.
    */
  lazy val maxInstancesPerOffer = opt[Int](
    "max_instances_per_offer",
    descr = "Max instances per offer. Do not start more than this number of app or pod instances on a single offer.",
    default = Some(5)
  )

  /**
    * This parameter controls how many offers are processed by offer matchers.
    * Not more than this number of offers get processed in parallel.
    * The default will be the number of processors limited by 8.
    */
  lazy val maxParallelOffers = opt[Int](
    "max_parallel_offers",
    descr = "[INTERNAL] The number of offers that are processed in parallel. The default value will be derived from the number of available processors in the system with a maximum of 8",
    default = Some(math.min(8, Runtime.getRuntime.availableProcessors())),
    hidden = true
  )

  /**
    * Offers that can not be processed immediately get queued.
    * The size of the queue is controlled by this property.
    * All offers that can not be processed and does not fit into the queue will be declined immediately.
    */
  lazy val maxQueuedOffers = opt[Int](
    "max_queued_offers",
    descr = "[INTERNAL] The number of offers queued for processing",
    default = Some(1024),
    hidden = true
  )
}
