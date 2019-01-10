package mesosphere.marathon

import akka.Done
import akka.actor._
import akka.event.{EventStream, LoggingReceive}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.{DeploymentManager, DeploymentPlan, ScalingProposition}
import mesosphere.marathon.core.election.LeadershipTransition
import mesosphere.marathon.core.event.DeploymentSuccess
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, RunSpec}
import mesosphere.marathon.storage.repository.{DeploymentRepository, GroupRepository}
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.Constraints
import org.apache.mesos
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class MarathonSchedulerActor private (
    groupRepository: GroupRepository,
    schedulerActions: SchedulerActions,
    deploymentManager: DeploymentManager,
    deploymentRepository: DeploymentRepository,
    historyActorProps: Props,
    healthCheckManager: HealthCheckManager,
    killService: KillService,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    leadershipTransitionEvents: Source[LeadershipTransition, Cancellable],
    eventBus: EventStream,
    instanceTracker: InstanceTracker)(implicit val mat: Materializer) extends Actor
  with StrictLogging with Stash {
  import context.dispatcher
  import mesosphere.marathon.MarathonSchedulerActor._

  /**
    * About locks:
    * - a lock is acquired if deployment is started
    * - a lock is acquired if a kill operation is executed
    * - a lock is acquired if a scale operation is executed
    *
    * This basically means:
    * - a kill/scale operation should not be performed, while a deployment is in progress
    * - a deployment should not be started, if a scale/kill operation is in progress
    * Since multiple conflicting deployment can be handled at the same time lockedRunSpecs saves
    * the lock count for each affected PathId. Lock is removed if lock count == 0.
    */
  val lockedRunSpecs = collection.mutable.Map[PathId, Int]().withDefaultValue(0)
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[Status]] = None
  var electionEventsSubscription: Option[Cancellable] = None

  override def preStart(): Unit = {
    historyActor = context.actorOf(historyActorProps, "HistoryActor")
    electionEventsSubscription = Some(leadershipTransitionEvents.to(Sink.foreach(self ! _)).run)
  }

  override def postStop(): Unit = {
    electionEventsSubscription.foreach(_.cancel())
  }

  def receive: Receive = suspended

  def suspended: Receive = LoggingReceive.withLabel("suspended"){
    case LeadershipTransition.ElectedAsLeaderAndReady =>
      logger.info("Starting scheduler actor")

      deploymentRepository.all().runWith(Sink.seq).onComplete {
        case Success(deployments) => self ! LoadedDeploymentsOnLeaderElection(deployments)
        case Failure(t) =>
          logger.error(s"Failed to recover deployments from repository: $t")
          self ! LoadedDeploymentsOnLeaderElection(Nil)
      }

    case LoadedDeploymentsOnLeaderElection(deployments) =>
      deployments.foreach { plan =>
        logger.info(s"Recovering deployment:\n$plan")
        deploy(context.system.deadLetters, Deploy(plan, force = false))
      }

      logger.info("Scheduler actor ready")
      unstashAll()
      context.become(started)
      self ! ReconcileHealthChecks

    case LeadershipTransition.Standby =>
    // ignored
    // FIXME: When we get this while recovering deployments, we become active anyway
    // and drop this message.

    case _ => stash()
  }

  def started: Receive = LoggingReceive.withLabel("started") {
    case LeadershipTransition.Standby =>
      logger.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      lockedRunSpecs.clear()
      context.become(suspended)

    case LeadershipTransition.ElectedAsLeaderAndReady => // ignore

    case ReconcileTasks =>
      import akka.pattern.pipe
      import context.dispatcher
      val reconcileFuture = activeReconciliation match {
        case None =>
          logger.info("initiate task reconciliation")
          val newFuture = schedulerActions.reconcileTasks(driver)
          activeReconciliation = Some(newFuture)
          newFuture.failed.foreach {
            case NonFatal(e) => logger.error("error while reconciling tasks", e)
          }
          newFuture
            // the self notification MUST happen before informing the initiator
            // if we want to ensure that we trigger a new reconciliation for
            // the first call after the last ReconcileTasks.answer has been received.
            .andThen { case _ => self ! ReconcileFinished }
        case Some(active) =>
          logger.info("task reconciliation still active, reusing result")
          active
      }
      reconcileFuture.map(_ => ReconcileTasks.answer).pipeTo(sender)

    case ReconcileFinished =>
      logger.info("task reconciliation has finished")
      activeReconciliation = None

    case ReconcileHealthChecks =>
      schedulerActions.reconcileHealthChecks()

    case cmd @ CancelDeployment(plan) =>
      // The deployment manager will respond via the plan future/promise
      deploymentManager.cancel(plan)

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case DeploymentFinished(plan) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentSuccess(plan)

    case DeploymentFailed(plan, reason) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentFailed(plan, reason)

    case TasksKilled(runSpecId, _) => removeLock(runSpecId)

    case StartInstance(runSpec) =>
      if (noConflictsWith(Set(runSpec.id))) startInstance(runSpec)
      else logger.info(s"Did not start an instance for ${runSpec.id} because runSpec is locked in a deployment.")

    case DecommissionInstance(runSpec) =>
      if (noConflictsWith(Set(runSpec.id))) decommissionInstance(runSpec)
      else logger.info(s"Did not decommission an instance for ${runSpec.id} because runSpec is locked in a deployment.")

    case msg => logger.warn(s"Received unexpected message from ${sender()}: $msg")
  }

  private def startInstance(runSpec: RunSpec): Unit = {
    launchQueue.add(runSpec, 1)
    logger.debug(s"Successfully launched new instance for ${runSpec.id}")
  }

  private def decommissionInstance(runSpec: RunSpec): Future[Done] = async {
    val runningInstances = await(instanceTracker.specInstances(runSpec.id)).filter(_.isActive)

    def killToMeetConstraints(runningInstances: Seq[Instance], killCount: Int): Seq[Instance] =
      Constraints.selectInstancesToKill(runSpec, runningInstances, killCount)

    val ScalingProposition(instancesToKill, _) = ScalingProposition.propose(
      runningInstances, None, killToMeetConstraints, runSpec.instances, runSpec.killSelection)

    instancesToKill match {
      case Some(i :: tail) =>
        if (tail.nonEmpty) logger.warn(s"Expected to decommission exactly one instance for ${runSpec.id} but apparently there were more candidates available: ${tail.map(_.instanceId).mkString(",")}")
        if (i.hasReservation) instanceTracker.setGoal(i.instanceId, Goal.Stopped, GoalChangeReason.OverCapacity)
        else instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)

      case None => logger.error(s"Expected to decommission exactly one instance for ${runSpec.id} but no suitable candidates found!")
    }
    Done
  }

  /**
    * Tries to acquire the lock for the given runSpecIds.
    * If it succeeds it evalutes the by name reference, returning Some(result)
    * Otherwise, returns None, which should be interpretted as lock acquisition failure
    *
    * @param runSpecIds the set of runSpecIds for which to acquire the lock
    * @param f the by-name reference that is evaluated if the lock acquisition is successful
    */
  def withLockFor[A](runSpecIds: Set[PathId])(f: => A): Option[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    if (noConflictsWith(runSpecIds)) {
      addLocks(runSpecIds)
      Some(f)
    } else {
      None
    }
  }

  def noConflictsWith(runSpecIds: Set[PathId]): Boolean = {
    val conflicts = lockedRunSpecs.keySet intersect runSpecIds
    conflicts.isEmpty
  }

  def removeLocks(runSpecIds: Set[PathId]): Unit = runSpecIds.foreach(removeLock)
  def removeLock(runSpecId: PathId): Unit = {
    if (lockedRunSpecs.contains(runSpecId)) {
      val locks = lockedRunSpecs(runSpecId) - 1
      if (locks <= 0) lockedRunSpecs -= runSpecId else lockedRunSpecs(runSpecId) -= 1
      logger.debug(s"Removed lock for run spec: id=$runSpecId locks=$locks lockedRunSpec=$lockedRunSpecs")
    }
  }

  def addLocks(runSpecIds: Set[PathId]): Unit = runSpecIds.foreach(addLock)
  def addLock(runSpecId: PathId): Unit = {
    lockedRunSpecs(runSpecId) += 1
    logger.debug(s"Added to lock for run spec: id=$runSpecId locks=${lockedRunSpecs(runSpecId)} lockedRunSpec=$lockedRunSpecs")
  }

  def driver: SchedulerDriver = marathonSchedulerDriverHolder.driver.get

  def deploy(origSender: ActorRef, cmd: Deploy): Unit = {
    val plan = cmd.plan
    val runSpecIds = plan.affectedRunSpecIds

    // We raise the lock in any case for a deployment attempt.
    // Afterwards the deployment plan is sent to DeploymentManager. It will take care of cancelling
    // conflicting deployments, scheduling new one or (if there were conflicts but the deployment
    // is not forced) send to the original sender an AppLockedException with conflicting deployments.
    //
    // If a deployment is forced (and there exists an old one):
    // - the new deployment will be started
    // - the old deployment will be cancelled and release all claimed locks
    //
    // In the case of a DeploymentFinished or DeploymentFailed we lower the lock again. See the receiving methods
    addLocks(runSpecIds)

    deploymentManager.start(plan, cmd.force, origSender).onComplete{
      case Success(_) => self ! DeploymentFinished(plan)
      case Failure(t) => self ! DeploymentFailed(plan, t)
    }
  }

  def deploymentSuccess(plan: DeploymentPlan): Unit = {
    logger.info(s"Deployment ${plan.id}:${plan.version} of ${plan.targetIdsString} finished")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    logger.error(s"Deployment ${plan.id}:${plan.version} of ${plan.targetIdsString} failed", reason)
    Future.sequence(plan.affectedRunSpecIds.map(launchQueue.purge))
      .recover { case NonFatal(error) => logger.warn(s"Error during async purge: planId=${plan.id} for ${plan.targetIdsString}", error); Done }
      .foreach { _ => eventBus.publish(core.event.DeploymentFailed(plan.id, plan, reason = Some(reason.getMessage()))) }
  }
}

object MarathonSchedulerActor {
  def props(
    groupRepository: GroupRepository,
    schedulerActions: SchedulerActions,
    deploymentManager: DeploymentManager,
    deploymentRepository: DeploymentRepository,
    historyActorProps: Props,
    healthCheckManager: HealthCheckManager,
    killService: KillService,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    leadershipTransitionEvents: Source[LeadershipTransition, Cancellable],
    eventBus: EventStream,
    instanceTracker: InstanceTracker)(implicit mat: Materializer): Props = {
    Props(new MarathonSchedulerActor(
      groupRepository,
      schedulerActions,
      deploymentManager,
      deploymentRepository,
      historyActorProps,
      healthCheckManager,
      killService,
      launchQueue,
      marathonSchedulerDriverHolder,
      leadershipTransitionEvents,
      eventBus,
      instanceTracker
    ))
  }

  case class LoadedDeploymentsOnLeaderElection(deployments: Seq[DeploymentPlan])

  sealed trait Command {
    def answer: Event
  }

  case object ReconcileTasks extends Command {
    def answer: Event = TasksReconciled
  }

  private case object ReconcileFinished

  case object ReconcileHealthChecks

  case class StartInstance(runSpec: RunSpec) extends Command {
    def answer: Event = Noop
  }

  case class DecommissionInstance(runSpec: RunSpec) extends Command {
    def answer: Event = Noop
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class CancelDeployment(plan: DeploymentPlan) extends Command {
    override def answer: Event = DeploymentFinished(plan)
  }

  sealed trait Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable) extends Event
  case class DeploymentFinished(plan: DeploymentPlan) extends Event
  case class TasksKilled(runSpecId: PathId, taskIds: Seq[Instance.Id]) extends Event
  case class CommandFailed(cmd: Command, reason: Throwable) extends Event
  case object Noop extends Event // A stub for the fact that some commands do not send events
}

class SchedulerActions(
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends StrictLogging {

  /**
    * Make sure all runSpecs are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    *
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Future[Status] = async {
    val root = await(groupRepository.root())

    val runSpecIds = root.transitiveRunSpecIds.toSet
    val instances = await(instanceTracker.instancesBySpec())

    val knownTaskStatuses = runSpecIds.flatMap { runSpecId =>
      TaskStatusCollector.collectTaskStatusFor(instances.specInstances(runSpecId))
    }

    (instances.allSpecIdsWithInstances -- runSpecIds).foreach { unknownId =>
      val orphanedInstances = instances.specInstances(unknownId)
      logger.warn(s"Instances reference runSpec $unknownId, which does not exist. Will now decommission. [${orphanedInstances.map(_.instanceId)}].")
      logger.info(s"Will decommission orphaned instances of runSpec $unknownId : [${orphanedInstances.map(_.instanceId)}].")
      orphanedInstances.foreach { orphanedInstance =>
        instanceTracker.setGoal(orphanedInstance.instanceId, Goal.Decommissioned, GoalChangeReason.Orphaned)
      }
    }

    logger.info("Requesting task reconciliation with the Mesos master")
    logger.debug(s"Tasks to reconcile: $knownTaskStatuses")
    if (knownTaskStatuses.nonEmpty) driver.reconcileTasks(knownTaskStatuses.asJavaCollection) // linter:ignore UseIfExpression

    // in addition to the known statuses send an empty list to get the unknown
    driver.reconcileTasks(java.util.Arrays.asList())
  }

  def reconcileHealthChecks(): Unit = {
    groupRepository.root().flatMap { rootGroup =>
      healthCheckManager.reconcile(rootGroup.transitiveApps.toIndexedSeq)
    }
  }
}

/**
  * Provides means to collect Mesos TaskStatus information for reconciliation.
  */
object TaskStatusCollector {
  def collectTaskStatusFor(instances: Seq[Instance]): Seq[mesos.Protos.TaskStatus] = {
    instances.flatMap { instance =>
      instance.tasksMap.values.collect {
        // only tasks not confirmed by mesos does not have mesosStatus (condition Created)
        // OverdueTasksActor is taking care of those tasks, we don't need to reconcile them
        case task @ Task(_, _, Task.Status(_, _, Some(mesosStatus), _, _)) if !task.isTerminal =>
          mesosStatus
      }
    }(collection.breakOut)
  }
}
