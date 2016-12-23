package mesosphere.marathon
package upgrade

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.MarathonSchedulerActor.{ CommandFailed, DeploymentStarted, LoadedDeploymentsOnLeaderElection, RetrieveRunningDeployments, RunningDeployments }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.repository.DeploymentRepository
import mesosphere.marathon.stream.Sink
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

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
class DeploymentManager(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, ActorRef, KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, StorageProvider, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit val mat: Materializer) extends Actor {
  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentManager._

  private[this] val log = LoggerFactory.getLogger(getClass)

  val runningDeployments: mutable.Map[String, DeploymentInfo] = mutable.Map.empty
  val deploymentStatus: mutable.Map[String, DeploymentStepInfo] = mutable.Map.empty

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) => Stop
  }

  def receive: Receive = suspended

  // TODO (AD): suspend-on-leader-abdication behavior should be implemented with the help of
  // TODO (AD): LeadershipModule.startWhenLeader() when moved this class to a core component.
  def suspended: Receive = {
    // Should only be sent by MarathonSchedulerActor on leader election. Reads all deployments from the
    // repository and sends them to sender. MarathonSchedulerActor needs to create locks for all running
    // deployments before they can be started.
    case LoadDeploymentsOnLeaderElection =>
      log.info("Recovering all deployments on leader election")
      val recipient = sender()
      deploymentRepository.all().runWith(Sink.seq).onComplete {
        case Success(deployments) => recipient ! LoadedDeploymentsOnLeaderElection(deployments)
        case Failure(t) =>
          log.error(s"Failed to recover deployments from repository: $t")
          recipient ! LoadedDeploymentsOnLeaderElection(Nil)
      }
      context.become(started)
    case _ =>
    // All messages are ignored until master reelection
  }

  def started: Receive = {
    // Should only be sent by MarathonSchedulerActor on leader abdication. All the deployments are stopped
    // and the context becomes suspended. It's important not to receive DeploymentFinished messages from
    // running DeploymentActors because that will delete stored deployments from the repository.
    case ShutdownDeployments =>
      log.info("Shutting down all deployments on leader abdication")
      for ((_, DeploymentInfo(Some(ref), _, _, _)) <- runningDeployments)
        ref ! DeploymentActor.Shutdown
      runningDeployments.clear()
      deploymentStatus.clear()
      context.become(suspended)

    case CancelDeployment(id) =>
      cancelDeployment(sender(), id)

    case DeploymentFinished(plan) =>
      log.info(s"Removing ${plan.id} from list of running deployments")
      runningDeployments -= plan.id
      deploymentStatus -= plan.id
      deploymentRepository.delete(plan.id)

    case LaunchDeploymentActor(plan, origSender) if isScheduledDeployment(plan.id) =>
      log.info(s"Launching DeploymentActor for ${plan.id}")
      startDeployment(plan, origSender)

    case LaunchDeploymentActor(plan, _) =>
      log.info(s"Deployment ${plan.id} was already canceled or overridden by another one. Not proceeding with it")

    case stepInfo: DeploymentStepInfo => deploymentStatus += stepInfo.plan.id -> stepInfo

    case ReadinessCheckUpdate(id, result) => deploymentStatus.get(id).foreach { info =>
      deploymentStatus += id -> info.copy(readinessChecks = info.readinessChecks.updated(result.taskId, result))
    }

    case RetrieveRunningDeployments =>
      sender() ! RunningDeployments(deploymentStatus.values.to[Seq])

    case StartDeployment(plan, origSender, force) =>
      val conflicts = conflictingDeployments(plan)
      val hasConflicts = conflicts.nonEmpty
      val recipient = sender()

      if (!hasConflicts)                startNonConflictingDeployment(plan, origSender, recipient)
      else if (hasConflicts && !force)  giveUpConflictingDeployment(plan, origSender, force)
      else if (hasConflicts && force)   startConflictingDeployment(plan, conflicts, origSender, recipient)

    case CancelDeletedConflicts(plan, conflicts, recipient, origSender) if isScheduledDeployment(plan.id) =>
      cancelDeletedConflicts(plan, conflicts, recipient, origSender)

    case WaitForCanceledConflicts(plan, conflicts, origSender) if isScheduledDeployment(plan.id) =>
      waitForCanceledConflicts(plan, conflicts, origSender)

    case FailedRepositoryOperation(plan, recipient, reason) if isScheduledDeployment(plan.id) =>
      recipient ! DeploymentFailed(plan, reason)
      runningDeployments.remove(plan.id)
  }

  private def giveUpConflictingDeployment(plan: DeploymentPlan, origSender: ActorRef, force: Boolean): Unit = {
    log.info(s"Received new deployment plan ${plan.id}. Conflicts are detected and it is not forced, so it will not start")
    origSender ! CommandFailed(
      MarathonSchedulerActor.Deploy(plan, force),
      AppLockedException(conflictingDeployments(plan).map(_.plan.id)))
  }

  @SuppressWarnings(Array("all")) // async/await
  private def startNonConflictingDeployment(plan: DeploymentPlan, origSender: ActorRef, recipient: ActorRef): Unit = {
    log.info(s"Received new deployment plan ${plan.id}, no conflicts detected")
    markScheduled(plan)

    async {
      await(deploymentRepository.store(plan))
      log.info(s"Stored new deployment plan ${plan.id}")

      if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan)

      self ! LaunchDeploymentActor(plan, recipient)
    }.recover {
      case NonFatal(e) =>
        log.error(s"Couldn't start deployment ${plan.id}. Repository store failed with: $e")
        self ! FailedRepositoryOperation(plan, recipient, e)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def startConflictingDeployment(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef, recipient: ActorRef): Unit = {
    log.info(s"Received new forced deployment plan ${plan.id} Proceeding with canceling conflicts ${conflicts.map(_.plan.id)}")

    markScheduled(plan)

    async {
      await(Future.sequence(conflicts.map(p => deploymentRepository.delete(p.plan.id))))
      log.info(s"Removed conflicting deployments ${conflicts.map(_.plan.id)} from the repository")
      self ! CancelDeletedConflicts(plan, conflicts, recipient, origSender)
    }.recover {
      case NonFatal(e) =>
        log.info(s"Failed to start deployment ${plan.id}. Repository delete failed with: $e")
        self ! FailedRepositoryOperation(plan, recipient, e)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def waitForCanceledConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef) = {
    val toCancel = conflicts.filter(_.status == DeploymentStatus.Canceling)
    val cancellations: Seq[Future[Boolean]] = toCancel.flatMap(_.cancel)

    async {
      await(Future.sequence(cancellations))

      log.info(s"Conflicting deployments ${toCancel.map(_.plan.id)} for deployment ${plan.id} have been canceled")
      self ! LaunchDeploymentActor(plan, origSender)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def cancelDeletedConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], recipient: ActorRef, origSender: ActorRef): Unit = {
    // Check if the conflicts are still in running deployments (might be already finished) and if the conflict is:
    // [Scheduled] - remove from internal state (they haven't been started yet, so there is nothing to cancel),
    //               and tell MarathonSchedulerActor that it was canceled since it needs to remove the lock.
    // [Deploying] - cancel by spawning a StopActor and marking as [Canceling]
    // [Canceling] - Nothing to do here since this deployment is already being canceled
    conflicts.filter(info => runningDeployments.contains(info.plan.id)).foreach {
      case DeploymentInfo(_, p, DeploymentStatus.Scheduled, _) => runningDeployments.remove(p.id).map(info =>
        recipient ! DeploymentFailed(info.plan, new DeploymentCanceledException("The upgrade has been cancelled")))
      case DeploymentInfo(_, p, DeploymentStatus.Deploying, _) => stopDeployment(p.id)
      case DeploymentInfo(_, _, DeploymentStatus.Canceling, _) => // Nothing to do here - this deployment is already being canceled
    }

    async {
      await(deploymentRepository.store(plan))
      log.info(s"Stored new deployment plan ${plan.id}")

      if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan)

      self ! WaitForCanceledConflicts(plan, conflicts, recipient)
    }.recover{
      case NonFatal(e) =>
        log.error(s"Couldn't start deployment ${plan.id}. Repository store failed with: $e")
        self ! FailedRepositoryOperation(plan, recipient, e)
    }
  }

  private def cancelDeployment(sender: ActorRef, id: String) = {
    runningDeployments.get(id) match {
      case Some(DeploymentInfo(_, _, DeploymentStatus.Scheduled, _)) =>
        log.info(s"Canceling scheduled deployment $id.")
        runningDeployments.remove(id).map(info => sender ! DeploymentFailed(info.plan, new DeploymentCanceledException("The upgrade has been cancelled")))

      case Some(DeploymentInfo(Some(_), _, DeploymentStatus.Deploying, _)) =>
        log.info(s"Canceling deployment $id which is already in progress.")
        stopDeployment(id)

      case Some(DeploymentInfo(_, _, DeploymentStatus.Canceling, _)) =>
        log.warn(s"The deployment $id is already being canceled.")

      case Some(_) =>
        // This means we have a deployment with a [Deploying] status which has no DeploymentActor to cancel it.
        // This is clearly an invalid state and should never happen.
        log.error(s"Failed to cancel an invalid deployment ${runningDeployments.get(id)}")

      case None =>
        sender ! DeploymentFailed(
          DeploymentPlan.empty.copy(id = id),
          new DeploymentCanceledException("The upgrade has been cancelled"))
    }
  }

  /** Method saves new DeploymentInfo with status = [Scheduled] */
  private def markScheduled(plan: DeploymentPlan) = {
    runningDeployments += plan.id -> DeploymentInfo(plan = plan, status = DeploymentStatus.Scheduled)
  }

  /** Method spawns a DeploymentActor for the passed plan and saves new DeploymentInfo with status = [Scheduled] */
  private def startDeployment(plan: DeploymentPlan, origSender: ActorRef) = {
    val ref = context.actorOf(
      deploymentActorProps(
        self,
        origSender,
        killService,
        scheduler,
        plan,
        taskTracker,
        launchQueue,
        storage,
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
  private def stopDeployment(id: String) = {
    val info = runningDeployments(id)
    val stopFuture = stopActor(info.ref.get, new DeploymentCanceledException("The upgrade has been cancelled"))
    runningDeployments.update(id, info.copy(status = DeploymentStatus.Canceling, cancel = Some(stopFuture)))
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
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

object DeploymentManager {
  case class StartDeployment(plan: DeploymentPlan, origSender: ActorRef, force: Boolean = false)
  case class CancelDeployment(id: String)
  case object ShutdownDeployments
  case class WaitForCanceledConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef)
  case class CancelDeletedConflicts(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], recipient: ActorRef, origSender: ActorRef)
  case class DeploymentFinished(plan: DeploymentPlan)
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable)
  case class ReadinessCheckUpdate(deploymentId: String, result: ReadinessCheckResult)
  case class LaunchDeploymentActor(plan: DeploymentPlan, origSender: ActorRef)
  case class FailedRepositoryOperation(plan: DeploymentPlan, recipient: ActorRef, reason: Throwable)
  case object LoadDeploymentsOnLeaderElection

  case class DeploymentStepInfo(
      plan: DeploymentPlan,
      step: DeploymentStep,
      nr: Int,
      readinessChecks: Map[Task.Id, ReadinessCheckResult] = Map.empty) {
    lazy val readinessChecksByApp: Map[PathId, Seq[ReadinessCheckResult]] = {
      readinessChecks.values.groupBy(_.taskId.runSpecId).mapValues(_.to[Seq]).withDefaultValue(Seq.empty)
    }
  }

  case class DeploymentInfo(
    ref: Option[ActorRef] = None, // An ActorRef to the DeploymentActor if status = [Deploying]
    plan: DeploymentPlan, // Deployment plan
    status: DeploymentStatus, // Status can be [Scheduled], [Canceling] or [Deploying]
    cancel: Option[Future[Boolean]] = None) // Cancellation future if status = [Canceling]

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
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, ActorRef, KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, StorageProvider, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit mat: Materializer): Props = {
    Props(new DeploymentManager(taskTracker, killService, launchQueue,
      scheduler, storage, healthCheckManager, eventBus, readinessCheckExecutor, deploymentRepository, deploymentActorProps))
  }

}
