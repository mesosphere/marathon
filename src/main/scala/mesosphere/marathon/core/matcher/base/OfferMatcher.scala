package mesosphere.marathon
package core.matcher.base

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.state.PathId
import org.apache.mesos.{ Protos => Mesos }

import scala.concurrent.Future

object OfferMatcher {

  /**
    * A InstanceOp with a [[InstanceOpSource]].
    *
    * The [[InstanceOpSource]] is informed whether the op is ultimately send to Mesos or if it is rejected
    * (e.g. by throttling logic).
    */
  case class InstanceOpWithSource(source: InstanceOpSource, op: InstanceOp) {
    def instanceId: Instance.Id = op.instanceId
    def accept(): Unit = source.instanceOpAccepted(op)
    def reject(reason: String): Unit = source.instanceOpRejected(op, reason)
  }

  /**
    * Reply from an offer matcher to a MatchOffer. If the offer match could not match the offer in any way it should
    * simply leave the opsWithSource collection empty.
    *
    * To increase fairness between matchers, each normal matcher should schedule as few operations as possible per
    * offer per match, e.g. one for instance launches without reservations. Multiple launches could be used
    * if the instances need to be co-located or if the operations are intrinsically dependent on each other.
    * The OfferMultiplexer tries to summarize suitable matches from multiple offer matches into one response.
    *
    * A MatchedInstanceOps reply does not guarantee that these operations can actually be executed.
    * The launcher of message should setup some kind of timeout mechanism and handle
    * instanceOpAccepted/instanceOpRejected calls appropriately.
    *
    * @param offerId the identifier of the offer
    * @param opsWithSource the ops that should be executed on that offer including the source of each op
    * @param resendThisOffer true, if this offer could not be processed completely (e.g. timeout)
    *                        and should be resend and processed again
    */
  case class MatchedInstanceOps(
      offerId: Mesos.OfferID,
      opsWithSource: Seq[InstanceOpWithSource],
      resendThisOffer: Boolean = false) {

    /** all included [InstanceOp] without the source information. */
    val ops: Seq[InstanceOp] = opsWithSource.map(_.op)(collection.breakOut)
  }

  object MatchedInstanceOps {
    def noMatch(offerId: Mesos.OfferID, resendThisOffer: Boolean = false): MatchedInstanceOps =
      new MatchedInstanceOps(offerId, Seq.empty[InstanceOpWithSource], resendThisOffer = resendThisOffer)
  }

  trait InstanceOpSource {
    def instanceOpAccepted(instanceOp: InstanceOp): Unit
    def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit
  }
}

/**
  * Tries to match offers with some instances.
  */
trait OfferMatcher {
  /**
    * Process offer and return the ops that this matcher wants to execute on this offer.
    *
    * The offer matcher can expect either a instanceOpAccepted or a instanceOpRejected call
    * for every returned `org.apache.mesos.Protos.TaskInfo`.
    */
  def matchOffer(offer: Mesos.Offer): Future[OfferMatcher.MatchedInstanceOps]

  /**
    * We can optimize the offer routing for different offer matcher in case there are reserved resources.
    * A defined precedence is used to filter incoming offers with reservations that apply to this filter.
    * If the filter matches, the offer matcher manager has higher priority than other matchers.
    */
  def precedenceFor: Option[PathId] = None
}
