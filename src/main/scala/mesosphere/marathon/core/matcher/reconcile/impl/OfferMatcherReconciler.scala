package mesosphere.marathon.core.matcher.reconcile.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{ Group, Timestamp }
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ Offer, OfferID, Resource }
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.Future

/**
  * Matches task labels found in offer against known tasks/apps and
  *
  * * destroys unknown volumes
  * * unreserves unknown reservations
  *
  * In the future, we probably want to switch to a less agressive approach
  *
  * * by creating tasks in state "unknown" of unknown tasks which are then transitioned to state "garbage" after
  *   a delay
  * * and creating unreserved/destroy operations for tasks in state "garbage" only
  */
private[reconcile] class OfferMatcherReconciler(instanceTracker: InstanceTracker, groupRepository: GroupRepository)
    extends OfferMatcher {

  private val log = LoggerFactory.getLogger(getClass)

  import scala.concurrent.ExecutionContext.Implicits.global

  override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {

    val frameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)

    val resourcesByTaskId: Map[Instance.Id, Iterable[Resource]] = {
      // TODO(jdef) pods don't use resident resources yet. Once they're needed it's not clear whether the labels
      // will continue to be task IDs, or pod instance IDs
      import scala.collection.JavaConverters._
      offer.getResourcesList.asScala.groupBy(TaskLabels.taskIdForResource(frameworkId, _)).collect {
        case (Some(taskId), resources) => taskId -> resources
      }
    }

    processResourcesByInstanceId(offer, resourcesByTaskId)
  }

  /**
    * Generate auxiliary instance operations for an offer based on current instance status.
    * For example, if an instance is no longer required then any resident resources it's using should be released.
    */
  private[this] def processResourcesByInstanceId(
    offer: Offer, resourcesByInstanceId: Map[Instance.Id, Iterable[Resource]]): Future[MatchedInstanceOps] =
    {
      // do not query instanceTracker in the common case
      if (resourcesByInstanceId.isEmpty) Future.successful(MatchedInstanceOps.noMatch(offer.getId))
      else {
        def createInstanceOps(tasksByApp: InstancesBySpec, rootGroup: Group): MatchedInstanceOps = {

          // TODO(jdef) pods don't suport resident resources yet so we don't need to worry about including them here
          def spurious(taskId: Instance.Id): Boolean =
            tasksByApp.instance(taskId).isEmpty || rootGroup.app(taskId.runSpecId).isEmpty

          val instanceOps: immutable.Seq[InstanceOpWithSource] = resourcesByInstanceId.iterator.collect {
            case (instanceId, spuriousResources) if spurious(instanceId) =>
              val unreserveAndDestroy =
                InstanceOp.UnreserveAndDestroyVolumes(
                  stateOp = InstanceUpdateOperation.ForceExpunge(instanceId),
                  oldInstance = tasksByApp.instance(instanceId),
                  resources = spuriousResources.to[Seq]
                )
              log.warn(
                "removing spurious resources and volumes of {} because the instance does no longer exist",
                instanceId)
              InstanceOpWithSource(source(offer.getId), unreserveAndDestroy)
          }.toVector

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
      log.info(s"accepted unreserveAndDestroy for ${instanceOp.instanceId} in offer [${offerId.getValue}]")
    override def instanceOpRejected(instanceOp: InstanceOp, reason: String): Unit =
      log.info("rejected unreserveAndDestroy for {} in offer [{}]: {}", instanceOp.instanceId, offerId.getValue, reason)
  }
}
