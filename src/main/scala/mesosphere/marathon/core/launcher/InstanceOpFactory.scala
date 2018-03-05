package mesosphere.marathon
package core.launcher

import mesosphere.marathon.core.instance.{ Instance, LocalVolume }
import mesosphere.marathon.state.{ DiskSource, Region, RunSpec }
import mesosphere.mesos.protos.ResourceProviderID
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

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
    * @param instanceMap        a map of running tasks or reservations for the given run spec,
    *                           needed to check constraints and handle resident tasks
    * @param additionalLaunches the number of additional launches that has been requested
    * @param instanceId         the id of the instance which should be launched
    * @param localRegion        region where mesos master is running
    */
  case class Request(runSpec: RunSpec, offer: Mesos.Offer, instanceMap: Map[Instance.Id, Instance],
      additionalLaunches: Int, instanceId: Instance.Id, localRegion: Option[Region] = None) {

    def frameworkId: FrameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)
    def instances: Seq[Instance] = instanceMap.values.to[Seq]

    // For resident tasks
    def isForResidentRunSpec: Boolean = runSpec.isResident
    lazy val reserved: Seq[Instance] = instances.filter(_.isReserved)
    def hasWaitingReservations: Boolean = reserved.nonEmpty
    def numberOfWaitingReservations: Int = reserved.size

    val needToLaunch: Boolean = additionalLaunches > 0 && hasWaitingReservations
    val needToReserve: Boolean = numberOfWaitingReservations < additionalLaunches
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
