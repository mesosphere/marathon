package mesosphere.marathon
package core.launcher

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ Region, RunSpec }
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

/** Infers which TaskOps to create for given run spec and offers. */
trait InstanceOpFactory {

  /**
    * Match an offer request.
    *
    * @param request the offer request.
    * @return Either this request results in a Match with some InstanceOp or a NoMatch
    *         which describes why this offer request could not be matched.
    */
  def matchOfferRequest(request: InstanceOpFactory.Request): OfferMatchResult

}

object InstanceOpFactory {
  /**
    * @param runSpec the related run specification definition
    * @param offer the offer to match against
    * @param instanceMap a map of running tasks or reservations for the given run spec,
    *              needed to check constraints and handle resident tasks
    * @param additionalLaunches the number of additional launches that has been requested
    * @param localRegion region where mesos master is running
    */
  case class Request(runSpec: RunSpec, offer: Mesos.Offer, instanceMap: Map[Instance.Id, Instance],
      additionalLaunches: Int, localRegion: Option[Region] = None) {
    def frameworkId: FrameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)
    def instances: Seq[Instance] = instanceMap.values.to[Seq]
    lazy val reserved: Seq[Instance] = instances.filter(_.isReserved)
    def hasWaitingReservations: Boolean = reserved.nonEmpty
    def numberOfWaitingReservations: Int = reserved.size
    def isForResidentRunSpec: Boolean = runSpec.residency.isDefined
  }
}
