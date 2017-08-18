package mesosphere.marathon
package core.matcher.reconcile.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ Offer, OfferID, Resource }

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

  import mesosphere.marathon.core.async.ExecutionContexts.global

  override def matchOffer(offer: Offer): Future[MatchedInstanceOps] = {

    val frameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)

    val resourcesByInstanceId: Map[Instance.Id, Seq[Resource]] = {
      // TODO(PODS): don't use resident resources yet. Once they're needed it's not clear whether the labels
      // will continue to be task IDs, or pod instance IDs
      offer.getResourcesList.groupBy(TaskLabels.instanceIdForResource(frameworkId, _)).collect {
        case (Some(instanceId), resources) => instanceId -> resources.to[Seq]
      }
    }

    processResourcesByInstanceId(offer, resourcesByInstanceId)
  }

  /**
    * Generate auxiliary instance operations for an offer based on current instance status.
    * For example, if an instance is no longer required then any resident resources it's using should be released.
    */
  private[this] def processResourcesByInstanceId(
    offer: Offer, resourcesByInstanceId: Map[Instance.Id, Seq[Resource]]): Future[MatchedInstanceOps] =
    {
      // do not query instanceTracker in the common case
      if (resourcesByInstanceId.isEmpty) Future.successful(MatchedInstanceOps.noMatch(offer.getId))
      else {
        def createInstanceOps(instancesBySpec: InstancesBySpec, rootGroup: RootGroup): MatchedInstanceOps = {

          // TODO(jdef) pods don't support resident resources yet so we don't need to worry about including them here
          /* Was this task launched from a previous app definition, or a prior launch that did not clean up properly */
          def spurious(instanceId: Instance.Id): Boolean =
            instancesBySpec.instance(instanceId).isEmpty || rootGroup.app(instanceId.runSpecId).isEmpty

          val instanceOps: Seq[InstanceOpWithSource] = resourcesByInstanceId.collect {
            case (instanceId, spuriousResources) if spurious(instanceId) =>
              val unreserveAndDestroy =
                InstanceOp.UnreserveAndDestroyVolumes(
                  stateOp = InstanceUpdateOperation.ForceExpunge(instanceId),
                  oldInstance = instancesBySpec.instance(instanceId),
                  resources = spuriousResources
                )
              logger.warn(s"removing spurious resources and volumes of $instanceId because the instance no longer exist")
              InstanceOpWithSource(source(offer.getId), unreserveAndDestroy)
          }(collection.breakOut)

          MatchedInstanceOps(offer.getId, instanceOps, resendThisOffer = true)
        }

        // query in parallel
        val instancesBySpedFuture = instanceTracker.instancesBySpec()
        val rootGroupFuture = groupRepository.root()

        for { instancesBySpec <- instancesBySpedFuture; rootGroup <- rootGroupFuture }
          yield createInstanceOps(instancesBySpec, rootGroup)
      }
    }

  private[this] def source(offerId: OfferID) = new InstanceOpSource {
    override def instanceOpAccepted(instanceOp: InstanceOp): Unit =
      logger.info(s"accepted unreserveAndDestroy for ${instanceOp.instanceId} in offer [${offerId.getValue}]")
    override def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit =
      logger.info(s"rejected unreserveAndDestroy for ${instanceOp.instanceId} in offer [${offerId.getValue}]: $reason")
  }
}
