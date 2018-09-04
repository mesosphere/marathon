package mesosphere.marathon
package core.launcher

import mesosphere.marathon.state.{RunSpec, Timestamp}
import mesosphere.mesos.NoOfferMatchReason
import org.apache.mesos.{Protos => Mesos}

/**
  * Defines the offer match result based on the related offer for given runSpec.
  */
sealed trait OfferMatchResult {

  /**
    * Related RunSpec.
    */
  def runSpec: RunSpec

  /**
    * Related Mesos Offer.
    */
  def offer: Mesos.Offer

  /**
    * Timestamp of this match result decision
    */
  def timestamp: Timestamp
}

object OfferMatchResult {

  case class Match(runSpec: RunSpec, offer: Mesos.Offer, instanceOp: InstanceOp, timestamp: Timestamp) extends OfferMatchResult

  case class NoMatch(runSpec: RunSpec, offer: Mesos.Offer, reasons: Seq[NoOfferMatchReason], timestamp: Timestamp) extends OfferMatchResult

}

