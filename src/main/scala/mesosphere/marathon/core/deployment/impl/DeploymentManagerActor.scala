package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.MarathonSchedulerActor.{ DeploymentFailed, DeploymentStarted }
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStepInfo }
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor._
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.DeploymentRepository

import scala.async.Async.{ async, await }
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

// format: OFF
 /*
  * Basic deployment message flow:
  * ===========================================
  * 1. Every deployment starts with a StartDeployment message. In the simplest case when there are no conflicts
  *   deployment is added to the list of running deployments and saved in the repository.
  * 2. On completion of the store operation (which returns a Future) we proceed with the deployment by sending
  *   ourselves a LaunchDeploymentActor message.
  * 3. Upon receiving a LaunchDeploymentActor we check if the deployment is still scheduled. It can happen that
  *   while we're were waiting for the deployment to be stored another (forced) deployment canceled (and deleted
  *   from the repository) this one. In this case the deployment is discarded.
  * 4. When the deployment is finished, DeploymentActor sends a FinishedDeployment message which will remove it
  *   from the list of running deployment and delete from repository.
  *
  * Basic flow visualised:
  *
  * -> StartDeployment (1)    | - save deployment and mark it as [Scheduling]
  *                           | -> repository.store(plan).onComplete (2)
  *                           |     <- LaunchDeploymentActor
  *                           |
  * -> LaunchDeploymentActor  | - mark deployment as [Deploying]
  *                           | - spawn DeploymentActor (3)
  *                           |     <- FinishedDeployment
  *                           |
  * -> FinishedDeployment (4) | - remove from runningDeployments
  *                           | - delete from repository
  *                           ˅
  *
  * Deployment message flow with conflicts:
  * ===========================================
  * This is a more complicated flow since there are multiple steps that are performed asynchronously and each individually
  * can fail. Imagine a situation where we have already deployments A, B and C in process and a new deployment D arrives
  * which has conflicts with all 3 of existing ones. In this case we have to:
  *
  * - repository.delete(A, B and C)
  * - cancel(A, B and C)
  * - repository.store(D)
  * - deploy(D)
  *
  * Repository store() and delete() operations may fail individually hence we have here:
  * 3 deletes + 1 store = 4 operations that may fail. This is unlikely but still possible.
  *
  * The matter is even more complicated by the fact that during any of those operations a new and forced deployment E
  * my arrive for which all of existing ones are conflicts. Additionally current leader may abdicate at any moment and
  * the new leader has to find a consistent state in the repository from which it should be able to reconcile.
  *
  * Due to the fact that deployments are stored/deleted individually and that we're only saving the desired (future)
  * state makes a full and complete recovery very hard if possible. Thus deployment manager tries to leave the repository
  * consistent (no conflicting deployments are stored) so even in a case of failure it should be possible for:
  * - the new master to reconcile deployments and end in a valid (but might be incomplete) state
  * - framework user to resend the deployment (force = true) to achieve desired stat
  *
  * Current implementation:
  * ===========================================
  * 1. Find all conflicting deployments and mark new plan as [Scheduled]
  * 2. Delete all conflicts from the repository first. This way even if the master crashes we shouldn't have any
  *    conflicts stored. However in the worst case (a crash after delete() and before store() we could end up with old
  *    deployments canceled and new one not existing. This is where framework user has to resend the deployment. On
  *    competition of the delete send self a CancelDeletedConflicts message.
  *    In case of a failure the next forced deployment will go through conflicts and try to delete them again.
  * 3. Cancel all conflicting deployment by spawning a StopActor. The cancellation futures are saved and the deployments
  *    are marked as [Cancelling]. Store the new plan in the repository. On completion of the store operation send self
  *    a WaitForCanceledConflicts.
  *    Note: Only after the deployment is stored we can send the original sender a positive response.
  * 4. If the deployment is still scheduled we wait for all the cancellation futures of the conflicts to complete.
  *    When that's the case a LaunchDeploymentActor message is sent to ourselves and the rest of the deployment is
  *    equal to the basic flow.
  * If any of the steps fail we send recover by sending self a FailedRepositoryOperation message. If the deployment is
  * still scheduled and not being canceled by the next one than we remove it from internal state. Otherwise the next
  * deployment will take care of it.
  *
  * Handling conflicts visualised:
  * ===========================================
  *
  * -> StartDeployment (1, 2)       | - mark deployment as [Scheduled]
  *                                 | - repository.delete(conflicts)
  *                                 |    <- CancelDeletedConflicts
  *                                 |
  * -> CancelDeletedConflicts (3)   | - cancel conflicting deployments with StopActor
  *                                 | - mark them as [Canceled] and save the cancellation future
  *                                 | - repository.store(plan)
  *                                 |     <- WaitForCanceledConflicts
  *                                 |
  * -> WaitForCanceledConflicts (4) | -> Future.sequence(cancellations).onComplete (2.5)
  *                                 |     <- LaunchDeploymentActor
  * ...                             ˅
  *
  * Note: deployment repository serializes operations by the key (deployment id). This allows us to keep store/delete
  * operations of the dependant deployments in order.
  *
  * Future work:
  * ===========================================
  * - Master should be able to reconcile completely from repository state because both current and desired
  *   state are stored
  * - Multiple store/delete operations might be replaced by storing one deployment graph similar to how
  *   we handle groups
  * - Move conflict resolution/dependencies/other upgrade logic out into a separate layer
  */
// format: ON
class DeploymentManagerActor(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, Promise[Done], KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit val mat: Materializer) extends Actor with StrictLogging {
  import context.dispatcher

  val runningDeployments: mutable.Map[String, DeploymentInfo] = mutable.Map.empty
  val deploymentStatus: mutable.Map[String, DeploymentStepInfo] = mutable.Map.empty

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) => Stop
  }

  def receive: Receive = {

    case CancelDeployment(plan) =>
      sender() ! cancelDeployment(plan.id)

    case DeploymentFinished(plan) =>
      runningDeployments.remove(plan.id).map { _ =>
        logger.info(s"Removing ${plan.id} from list of running deployments")
        deploymentStatus -= plan.id
        deploymentRepository.delete(plan.id)
      }

    case LaunchDeploymentActor(plan) if isScheduledDeployment(plan.id) =>
      logger.info(s"Launching DeploymentActor for ${plan.id}")
      startDeployment(runningDeployments(plan.id))

    case LaunchDeploymentActor(plan) =>
      logger.info(s"Deployment ${plan.id} was already canceled or overridden by another one. Not proceeding with it")

    case stepInfo: DeploymentStepInfo => deploymentStatus += stepInfo.plan.id -> stepInfo

    case ReadinessCheckUpdate(id, result) => deploymentStatus.get(id).foreach { info =>
      deploymentStatus += id -> info.copy(readinessChecks = info.readinessChecks.updated(result.taskId, result))
    }

    case ListRunningDeployments =>
      sender() ! Future.successful(deploymentStatus.values.to[Seq])

    case StartDeployment(plan, origSender, force) =>
      val conflicts = conflictingDeployments(plan)
      val hasConflicts = conflicts.nonEmpty

      val result: Future[Done] =
        if (!hasConflicts)                startNonConflictingDeployment(plan, origSender)
        else if (hasConflicts && !force)  giveUpConflictingDeployment(plan, origSender)
        else if (hasConflicts && force)   startConflictingDeployment(plan, conflicts, origSender)
        else Future.failed(new IllegalStateException("Impossible deployment state"))

      sender() ! result

    case CancelDeletedConflicts(plan, conflicts, origSender) if isScheduledDeployment(plan.id) =>
      cancelDeletedConflicts(plan, conflicts, origSender)

    case WaitForCanceledConflicts(plan, conflicts) if isScheduledDeployment(plan.id) =>
      waitForCanceledConflicts(plan, conflicts)

    case FailedRepositoryOperation(plan, reason) if isScheduledDeployment(plan.id) =>
      runningDeployments.remove(plan.id).map(info => info.promise.failure(reason))
  }

  private def giveUpConflictingDeployment(plan: DeploymentPlan, origSender: ActorRef): Future[Done] = {
    logger.info(s"Received new deployment plan ${plan.id}. Conflicts are detected and it is not forced, so it will not start")
    val reason = AppLockedException(conflictingDeployments(plan).map(_.plan.id))
    origSender ! DeploymentFailed(plan, reason)

    Promise[Done]().failure(reason).future
  }

  @SuppressWarnings(Array("all")) // async/await
  private def startNonConflictingDeployment(plan: DeploymentPlan, origSender: ActorRef) = {
    logger.info(s"Received new deployment plan ${plan.id}, no conflicts detected")
    val result: Future[Done] = markScheduled(plan)

    async {
      await(deploymentRepository.store(plan))
      logger.info(s"Stored new deployment plan ${plan.id}")

      if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan)

      self ! LaunchDeploymentActor(plan)
    }.recover {
      case NonFatal(e) =>
        logger.error(s"Couldn't start deployment ${plan.id}. Repository store failed with:", e)
        self ! FailedRepositoryOperation(plan, e)
    }
    result
  }

  @SuppressWarnings(Array("all")) // async/await
  private def startConflictingDeployment(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef) = {
    logger.info(s"Received new forced deployment plan ${plan.id} Proceeding with canceling conflicts ${conflicts.map(_.plan.id)}")

    val result: Future[Done] = markScheduled(plan)

    async {
      await(Future.sequence(conflicts.map(p => deploymentRepository.delete(p.plan.id))))
      logger.info(s"Removed conflicting deployments ${conflicts.map(_.plan.id)} from the repository")
      self ! CancelDeletedConflicts(plan, conflicts, origSender)
    }.recover {
      case NonFatal(e) =>
        logger.info(s"Failed to start deployment ${plan.id}. Repository delete failed with: $e")
        self ! FailedRepositoryOperation(plan, e)
    }
    result
  }

  @SuppressWarnings(Array("all")) // async/await
  private def waitForCanceledConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo]) = {
    val toCancel = conflicts.filter(_.status == DeploymentStatus.Canceling)
    val cancellations: Seq[Future[Done]] = toCancel.flatMap(_.cancel)

    async {
      await(Future.sequence(cancellations))

      logger.info(s"Conflicting deployments ${toCancel.map(_.plan.id)} for deployment ${plan.id} have been canceled")
      self ! LaunchDeploymentActor(plan)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def cancelDeletedConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef): Unit = {
    // Check if the conflicts are still in running deployments (might be already finished) and if the conflict is:
    // [Scheduled] - remove from internal state (they haven't been started yet, so there is nothing to cancel),
    //               and tell MarathonSchedulerActor that it was canceled since it needs to remove the lock.
    // [Deploying] - cancel by spawning a StopActor and marking as [Canceling]
    // [Canceling] - Nothing to do here since this deployment is already being canceled
    conflicts.filter(info => runningDeployments.contains(info.plan.id)).foreach {
      case DeploymentInfo(_, p, DeploymentStatus.Scheduled, _, _) => runningDeployments.remove(p.id).map(info =>
        info.promise.failure(new DeploymentCanceledException("The upgrade has been cancelled")))
      case DeploymentInfo(_, p, DeploymentStatus.Deploying, _, _) => stopDeployment(p.id)
      case DeploymentInfo(_, _, DeploymentStatus.Canceling, _, _) => // Nothing to do here - this deployment is already being canceled
    }

    async {
      await(deploymentRepository.store(plan))
      logger.info(s"Stored new deployment plan ${plan.id}")

      if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan)

      self ! WaitForCanceledConflicts(plan, conflicts)
    }.recover{
      case NonFatal(e) =>
        logger.error(s"Couldn't start deployment ${plan.id}. Repository store failed with: $e")
        self ! FailedRepositoryOperation(plan, e)
    }
  }

  private def cancelDeployment(id: String): Future[Done] = {
    runningDeployments.get(id) match {
      case Some(DeploymentInfo(_, _, DeploymentStatus.Scheduled, _, _)) =>
        logger.info(s"Canceling scheduled deployment $id.")
        runningDeployments.remove(id).map(info => info.promise.failure(new DeploymentCanceledException("The upgrade has been cancelled")))
        Future.successful(Done)

      case Some(DeploymentInfo(Some(_), _, DeploymentStatus.Deploying, _, _)) =>
        logger.info(s"Canceling deployment $id which is already in progress.")
        stopDeployment(id)

      case Some(DeploymentInfo(_, _, DeploymentStatus.Canceling, _, _)) =>
        logger.warn(s"The deployment $id is already being canceled.")
        Future.successful(Done)

      case Some(_) =>
        // This means we have a deployment with a [Deploying] status which has no DeploymentActor to cancel it.
        // This is clearly an invalid state and should never happen.
        logger.error(s"Failed to cancel an invalid deployment ${runningDeployments.get(id)}")
        Future.failed(new IllegalStateException(s"Failed to cancel an invalid deployment ${runningDeployments.get(id)}"))

      case None =>
        Future.failed(new DeploymentCanceledException("The upgrade has been cancelled"))
    }
  }

  /** Method saves new DeploymentInfo with status = [Scheduled] */
  private def markScheduled(plan: DeploymentPlan): Future[Done] = {
    val promise = Promise[Done]()
    runningDeployments += plan.id -> DeploymentInfo(plan = plan, status = DeploymentStatus.Scheduled, promise = promise)
    promise.future
  }

  /** Method spawns a DeploymentActor for the passed plan and saves new DeploymentInfo with status = [Scheduled] */
  private def startDeployment(info: DeploymentInfo) = {
    val plan = info.plan
    val ref = context.actorOf(
      deploymentActorProps(
        self,
        info.promise,
        killService,
        scheduler,
        plan,
        taskTracker,
        launchQueue,
        healthCheckManager,
        eventBus,
        readinessCheckExecutor
      ),
      plan.id
    )
    runningDeployments.update(plan.id, runningDeployments(plan.id).copy(ref = Some(ref), status = DeploymentStatus.Deploying))
  }

  /** Method spawns a StopActor for the passed plan Id and saves new DeploymentInfo with status = [Canceling] */
  @SuppressWarnings(Array("OptionGet"))
  private def stopDeployment(id: String): Future[Done] = {
    val info = runningDeployments(id)
    val stopFuture = stopActor(info.ref.get, new DeploymentCanceledException("The upgrade has been cancelled"))
    runningDeployments.update(id, info.copy(status = DeploymentStatus.Canceling, cancel = Some(stopFuture)))
    stopFuture
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Done] = {
    val promise = Promise[Done]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }

  def isScheduledDeployment(id: String): Boolean = {
    runningDeployments.contains(id) && runningDeployments(id).status == DeploymentStatus.Scheduled
  }

  def hasConflicts(plan: DeploymentPlan): Boolean = {
    conflictingDeployments(plan).nonEmpty
  }

  /**
    * Methods return all deployments that are conflicting with passed plan.
    */
  def conflictingDeployments(thisPlan: DeploymentPlan): Seq[DeploymentInfo] = {
    def intersectsWith(thatPlan: DeploymentPlan): Boolean = {
      thatPlan.affectedRunSpecIds.intersect(thisPlan.affectedRunSpecIds).nonEmpty
    }
    runningDeployments.values.filter(info => intersectsWith(info.plan)).to[Seq]
  }
}

object DeploymentManagerActor {
  case class StartDeployment(plan: DeploymentPlan, origSender: ActorRef, force: Boolean = false)
  case class CancelDeployment(plan: DeploymentPlan)
  case object ListRunningDeployments
  case class WaitForCanceledConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo])
  case class CancelDeletedConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef)
  case class DeploymentFinished(plan: DeploymentPlan)
  case class ReadinessCheckUpdate(deploymentId: String, result: ReadinessCheckResult)
  case class LaunchDeploymentActor(plan: DeploymentPlan)
  case class FailedRepositoryOperation(plan: DeploymentPlan, reason: Throwable)

  case class DeploymentInfo(
    ref: Option[ActorRef] = None, // An ActorRef to the DeploymentActor if status = [Deploying]
    plan: DeploymentPlan, // Deployment plan
    status: DeploymentStatus, // Status can be [Scheduled], [Canceling] or [Deploying]
    cancel: Option[Future[Done]] = None, // Cancellation future if status = [Canceling]
    promise: Promise[Done]) // Deployment promise

  sealed trait DeploymentStatus
  object DeploymentStatus {
    case object Scheduled extends  DeploymentStatus
    case object Canceling extends  DeploymentStatus
    case object Deploying extends  DeploymentStatus
  }

  @SuppressWarnings(Array("MaxParameters"))
  def props(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, Promise[Done], KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit mat: Materializer): Props = {
    Props(new DeploymentManagerActor(taskTracker, killService, launchQueue,
      scheduler, healthCheckManager, eventBus, readinessCheckExecutor, deploymentRepository, deploymentActorProps))
  }

}
