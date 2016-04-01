package mesosphere.marathon.core.matcher.reconcile.impl

import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpSource, TaskOpWithSource }
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.TasksByApp
import mesosphere.marathon.state.{ Group, GroupRepository, Timestamp }
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
private[reconcile] class OfferMatcherReconciler(taskTracker: TaskTracker, groupRepository: GroupRepository)
    extends OfferMatcher {

  private val log = LoggerFactory.getLogger(getClass)

  import scala.concurrent.ExecutionContext.Implicits.global

  override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTaskOps] = {

    val frameworkId = FrameworkId("").mergeFromProto(offer.getFrameworkId)

    val resourcesByTaskId: Map[Id, Iterable[Resource]] = {
      import scala.collection.JavaConverters._
      offer.getResourcesList.asScala.groupBy(TaskLabels.taskIdForResource(frameworkId, _)).collect {
        case (Some(taskId), resources) => taskId -> resources
      }
    }

    processResourcesByTaskId(offer, resourcesByTaskId)
  }

  private[this] def processResourcesByTaskId(
    offer: Offer, resourcesByTaskId: Map[Id, Iterable[Resource]]): Future[MatchedTaskOps] =
    {
      // do not query taskTracker in the common case
      if (resourcesByTaskId.isEmpty) Future.successful(MatchedTaskOps.noMatch(offer.getId))
      else {
        def createTaskOps(tasksByApp: TasksByApp, rootGroup: Group): MatchedTaskOps = {
          def spurious(taskId: Id): Boolean =
            tasksByApp.task(taskId).isEmpty || rootGroup.app(taskId.appId).isEmpty

          val taskOps = resourcesByTaskId.iterator.collect {
            case (taskId, spuriousResources) if spurious(taskId) =>
              val unreserveAndDestroy =
                TaskOp.UnreserveAndDestroyVolumes(
                  stateOp = TaskStateOp.ForceExpunge(taskId),
                  oldTask = tasksByApp.task(taskId),
                  resources = spuriousResources.to[Seq]
                )
              TaskOpWithSource(source(offer.getId), unreserveAndDestroy)
          }.to[Seq]

          MatchedTaskOps(offer.getId, taskOps, resendThisOffer = true)
        }

        // query in parallel
        val tasksByAppFuture = taskTracker.tasksByApp()
        val rootGroupFuture = groupRepository.rootGroupOrEmpty()

        for { tasksByApp <- tasksByAppFuture; rootGroup <- rootGroupFuture } yield createTaskOps(tasksByApp, rootGroup)
      }
    }

  private[this] def source(offerId: OfferID) = new TaskOpSource {
    override def taskOpAccepted(taskOp: TaskOp): Unit =
      log.info(s"accepted unreserveAndDestroy for ${taskOp.taskId} in offer [${offerId.getValue}]")
    override def taskOpRejected(taskOp: TaskOp, reason: String): Unit =
      log.info("rejected unreserveAndDestroy for {} in offer [{}]: {}", taskOp.taskId, offerId.getValue, reason)
  }
}
