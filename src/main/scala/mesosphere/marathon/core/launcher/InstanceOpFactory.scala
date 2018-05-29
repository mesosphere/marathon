package mesosphere.marathon
package core.launcher

import mesosphere.marathon.core.instance.{Instance, LocalVolume}
import mesosphere.marathon.state.{DiskSource, Region, RunSpec}
import mesosphere.mesos.protos.ResourceProviderID
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{Protos => Mesos}

/** Infers which TaskOps to create for given run spec and offers. */
trait InstanceOpFactory {

  /**
    * Match an offer request.
    *
    * @param request the offer request.
    * @return        either this request results in a Match with some InstanceOp or a NoMatch
    *                which describes why this offer request could not be matched
    */
  def matchOfferRequest(request: InstanceOpFactory.Request): OfferMatchResult

}

object InstanceOpFactory {
  /**
    * @param runSpec            the related run specification definition
    * @param offer              the offer to match against
    * @param instances          all running instances or reservations for the given run spec,
    *                           needed to check constraints and handle resident tasks
    * @param scheduledInstances instances that await offer matching
    * @param localRegion        region where Mesos master is running
    */
  case class Request(
      runSpec: RunSpec,
      offer: Mesos.Offer,
      instances: Seq[Instance],
      scheduledInstances: Iterable[Instance],
      localRegion: Option[Region] = None) {
    def frameworkId: FrameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)
    lazy val reserved: Seq[Instance] = instances.filter(_.isReserved)
    def hasWaitingReservations: Boolean = reserved.nonEmpty
    def numberOfWaitingReservations: Int = reserved.size
    def isForResidentRunSpec: Boolean = runSpec.isResident
  }

  /**
    * An instance local volume with all information which is necessary for a volume creation
    * upon offer receive.
    *
    * @param providerId an optional resource provider ID
    * @param source     a disk source from an offer received
    * @param volume     a instance local volume
    */
  case class OfferedVolume(
      providerId: Option[ResourceProviderID],
      source: DiskSource,
      volume: LocalVolume)
}
