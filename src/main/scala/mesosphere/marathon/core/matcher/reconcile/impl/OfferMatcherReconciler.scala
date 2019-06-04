package mesosphere.marathon
package core.matcher.reconcile.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.{Instance, Reservation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.{Offer, OfferID, Resource}

import scala.async.Async.{async, await}
import scala.concurrent.Future

/**
  * Matches task labels found in offer against known tasks/apps and
  *
  * * destroys unknown volumes
  * * unreserves unknown reservations
  *
  * In the future, we probably want to switch to a less aggressive approach
  *
  * * by creating tasks in state "unknown" of unknown tasks which are then transitioned to state "garbage" after
  *   a delay
  * * and creating unreserved/destroy operations for tasks in state "garbage" only
  */
private[reconcile] class OfferMatcherReconciler(instanceTracker: InstanceTracker, groupRepository: GroupRepository)
  extends OfferMatcher with StrictLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  override def matchOffer(offer: Offer): Future[MatchedInstanceOps] = {

    val resourcesByReservationId: Map[Reservation.Id, Seq[Resource]] = {
      offer.getResourcesList.groupBy(TaskLabels.reservationFromResource(_)).collect {
        case (Some(reservationId), resources) => reservationId -> resources.to[Seq]
      }
    }

    processResourcesByReservationId(offer, resourcesByReservationId)
  }

  /**
    * Generate auxiliary instance operations for an offer based on current instance status.
    * For example, if an instance is no longer required then any resident resources it's using should be released.
    */
  private[this] def processResourcesByReservationId(
    offer: Offer, resourcesByReservationId: Map[Reservation.Id, Seq[Resource]]): Future[MatchedInstanceOps] =
    {
      // do not query instanceTracker in the common case
      if (resourcesByReservationId.isEmpty) Future.successful(MatchedInstanceOps.noMatch(offer.getId))
      else {
        async {
          val instancesBySpec = await(instanceTracker.instancesBySpec())
          val knownReservationIds: Set[Reservation.Id] = instancesBySpec.allInstances.collect {
            case Instance(_, _, _, _, _, Some(Reservation(_, _, reservationId))) => reservationId
          }(collection.breakOut)

          createInstanceOps(offer, knownReservationIds, resourcesByReservationId)
        }
      }
    }

  def createInstanceOps(offer: Offer, knownReservationIds: Set[Reservation.Id], resourcesByReservationId: Map[Reservation.Id, Seq[Resource]]): MatchedInstanceOps = {

    val instanceOps: Seq[InstanceOpWithSource] = resourcesByReservationId.collect {
      // TODO: Should we also unreserve terminal instances with goal == Decommission?
      case (reservationId, spuriousResources) if !knownReservationIds.contains(reservationId) =>
        val unreserveAndDestroy =
          InstanceOp.UnreserveAndDestroyVolumes(
            stateOp = InstanceUpdateOperation.Noop,
            oldInstance = None,
            resources = spuriousResources
          )
        logger.warn(s"removing spurious resources and volumes of reservation $reservationId because the instance no longer exist")
        InstanceOpWithSource(source(offer.getId), unreserveAndDestroy)
    }(collection.breakOut)

    MatchedInstanceOps(offer.getId, instanceOps, resendThisOffer = true)
  }

  private[this] def source(offerId: OfferID) = new InstanceOpSource {
    override def instanceOpAccepted(instanceOp: InstanceOp): Unit =
      logger.info(s"accepted unreserveAndDestroy for ${instanceOp.instanceId} in offer [${offerId.getValue}]")
    override def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit =
      logger.info(s"rejected unreserveAndDestroy for ${instanceOp.instanceId} in offer [${offerId.getValue}]: $reason")
  }
}
