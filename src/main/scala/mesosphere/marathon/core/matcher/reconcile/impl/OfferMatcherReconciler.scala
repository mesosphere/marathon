package mesosphere.marathon.core.matcher.reconcile.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpSource, TaskOpWithSource }
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{ Group, Timestamp }
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ Offer, OfferID, Resource }
import org.slf4j.LoggerFactory

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
private[reconcile] class OfferMatcherReconciler(taskTracker: InstanceTracker, groupRepository: GroupRepository)
    extends OfferMatcher {

  private val log = LoggerFactory.getLogger(getClass)

  import scala.concurrent.ExecutionContext.Implicits.global

  override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTaskOps] = {

    val frameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)

    val resourcesByTaskId: Map[Instance.Id, Iterable[Resource]] = {
      import scala.collection.JavaConverters._
      offer.getResourcesList.asScala.groupBy(TaskLabels.taskIdForResource(frameworkId, _)).collect {
        case (Some(taskId), resources) => taskId -> resources
      }
    }

    processResourcesByInstanceId(offer, resourcesByTaskId)
  }

  // TODO POD support
  private[this] def processResourcesByInstanceId(
    offer: Offer, resourcesByInstanceId: Map[Instance.Id, Iterable[Resource]]): Future[MatchedTaskOps] =
    {
      // do not query taskTracker in the common case
      if (resourcesByInstanceId.isEmpty) Future.successful(MatchedTaskOps.noMatch(offer.getId))
      else {
        def createTaskOps(tasksByApp: InstancesBySpec, rootGroup: Group): MatchedTaskOps = {
          def spurious(taskId: Instance.Id): Boolean =
            tasksByApp.task(taskId).isEmpty || rootGroup.app(taskId.runSpecId).isEmpty

          val taskOps = resourcesByInstanceId.iterator.collect {
            case (instanceId, spuriousResources) if spurious(instanceId) =>
              val unreserveAndDestroy =
                InstanceOp.UnreserveAndDestroyVolumes(
                  stateOp = TaskStateOp.ForceExpunge(instanceId),
                  oldInstance = tasksByApp.task(instanceId),
                  resources = spuriousResources.to[Seq]
                )
              log.warn("removing spurious resources and volumes of {} because the app does no longer exist", instanceId)
              TaskOpWithSource(source(offer.getId), unreserveAndDestroy)
          }.to[Seq]

          MatchedTaskOps(offer.getId, taskOps, resendThisOffer = true)
        }

        // query in parallel
        val tasksByAppFuture = taskTracker.instancessBySpec()
        val rootGroupFuture = groupRepository.root()

        for { tasksByApp <- tasksByAppFuture; rootGroup <- rootGroupFuture } yield createTaskOps(tasksByApp, rootGroup)
      }
    }

  private[this] def source(offerId: OfferID) = new TaskOpSource {
    override def taskOpAccepted(taskOp: InstanceOp): Unit =
      log.info(s"accepted unreserveAndDestroy for ${taskOp.instanceId} in offer [${offerId.getValue}]")
    override def taskOpRejected(taskOp: InstanceOp, reason: String): Unit =
      log.info("rejected unreserveAndDestroy for {} in offer [{}]: {}", taskOp.instanceId, offerId.getValue, reason)
  }
}
