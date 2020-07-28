package mesosphere.marathon

import akka.Done
import akka.actor._
import akka.event.{EventStream, LoggingReceive}
import akka.pattern.pipe
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
import mesosphere.marathon.core.task.termination.impl.KillStreamWatcher
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{AbsolutePathId, RunSpec}
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
    eventBus: EventStream)(implicit val mat: Materializer) extends Actor
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
  val lockedRunSpecs = collection.mutable.Map.empty[AbsolutePathId, Long]
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[Status]] = None
  var electionEventsSubscription: Option[Cancellable] = None

  var currentLockVersion: Long = 0

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

    case ScaleRunSpecs => scaleRunSpecs()

    case cmd @ ScaleRunSpec(runSpecId) =>
      logger.debug("Receive scale run spec for {}", runSpecId)

      withLockFor(Set(runSpecId)) { lockVersion =>
        val result: Future[Event] = schedulerActions
          .scale(runSpecId)
          .map { _ =>
            self ! cmd.answer
            cmd.answer
          }
          .recover {
            case ex =>
              logger.error(s"Scale run spec failed. runSpecId=$runSpecId", ex)
              CommandFailed(cmd, ex)
          }

        // Always release the lock.
        result.onComplete(_ => self ! RemoveLocks(runSpecId :: Nil, lockVersion))

        if (sender != context.system.deadLetters)
          result.pipeTo(sender)
      } match {
        case None =>
          // ScaleRunSpec is not a user initiated command
          logger.info(s"Did not try to scale run spec $runSpecId; it is locked")
        case _ =>
      }

    case RemoveLocks(runSpecIds, lockVersion) =>
      removeLocks(runSpecIds, lockVersion)

    case cmd @ CancelDeployment(plan) =>
      // The deployment manager will respond via the plan future/promise
      deploymentManager.cancel(plan)

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case DeploymentFinished(plan) =>
      deploymentSuccess(plan)

    case DeploymentFailed(plan, reason) =>
      deploymentFailed(plan, reason)

    case RunSpecScaled(id) => () // The lock is released via RemoveLocks.
    case msg => logger.warn(s"Received unexpected message from ${sender()}: $msg")
  }

  def scaleRunSpecs(): Unit = {
    groupRepository.root().foreach { root =>
      root.transitiveRunSpecs.foreach(spec => self ! ScaleRunSpec(spec.id))
    }
  }

  /**
    * Tries to acquire the lock for the given runSpecIds.
    * If it succeeds it evaluates the by name reference, returning Some(result)
    * Otherwise, returns None, which should be interpreted as lock acquisition failure
    *
    * @param runSpecIds the set of runSpecIds for which to acquire the lock
    * @param f the by-name reference that is evaluated if the lock acquisition is successful
    */
  def withLockFor[A](runSpecIds: Set[AbsolutePathId])(f: Long => A): Option[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    if (noConflictsWith(runSpecIds)) {
      val lockVersion = addLocks(runSpecIds)
      Some(f(lockVersion))
    } else {
      logger.info(s"Run specs are locked: ids=[${runSpecIds.mkString(", ")}] lockedRunSpecs=$lockedRunSpecs")
      None
    }
  }

  def noConflictsWith(runSpecIds: Set[AbsolutePathId]): Boolean = {
    val conflicts = lockedRunSpecs.keySet intersect runSpecIds
    conflicts.isEmpty
  }

  def removeLocks(runSpecIds: Iterable[AbsolutePathId], lockVersion: Long): Unit =
    runSpecIds.foreach { runSpecId => removeLock(runSpecId, lockVersion) }
  def removeLock(runSpecId: AbsolutePathId, lockVersion: Long): Unit = {
    // Only remove if we are at the latest lock version. Eg let's say we have two scale events for
    // app /foo after another. The first scale check locks with version 1. The seconds will lock
    // with version 2 after the lock for v1 was released. This check will then prevent that a delayed
    // duplicated lock release for v1 will release v2.
    if (lockedRunSpecs.get(runSpecId).contains(lockVersion)) {
      lockedRunSpecs.remove(runSpecId)
      logger.debug(
        s"Removed lock for run spec: id=$runSpecId lockedRunSpecs=$lockedRunSpecs lockVersion=$lockVersion"
      )
    } else {
      logger.warn(
        s"Cannot release lock for run spec: id=$runSpecId lockedRunSpec=$lockedRunSpecs lockVersion=$lockVersion"
      )
    }
  }

  def getNextLockVersion(): Long = {
    currentLockVersion += 1
    currentLockVersion
  }
  def addLocks(runSpecIds: Set[AbsolutePathId]): Long = {
    val lockVersion = getNextLockVersion()
    runSpecIds.foreach { id => addLock(id, lockVersion) }
    lockVersion
  }

  def addLock(runSpecId: AbsolutePathId, lockVersion: Long): Unit = {
    lockedRunSpecs(runSpecId) = lockVersion
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
    val lockVersion = addLocks(runSpecIds)

    deploymentManager.start(plan, cmd.force, origSender).onComplete {
      case Success(_) =>
        self ! DeploymentFinished(plan)
        self ! RemoveLocks(plan.affectedRunSpecIds, lockVersion)

      case Failure(t) =>
        self ! DeploymentFailed(plan, t)
        self ! RemoveLocks(plan.affectedRunSpecIds, lockVersion)
    }
  }

  def deploymentSuccess(plan: DeploymentPlan): Unit = {
    logger.info(s"Deployment ${plan.id}:${plan.version} of ${plan.targetIdsString} finished")
    eventBus.publish(DeploymentSuccess(plan.id, Raml.toRaml(plan)))
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    logger.error(s"Deployment ${plan.id}:${plan.version} of ${plan.targetIdsString} failed", reason)
    eventBus.publish(core.event.DeploymentFailed(plan.id, Raml.toRaml(plan), reason = Some(reason.getMessage())))
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
    eventBus: EventStream)(implicit mat: Materializer): Props = {
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
      eventBus
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

  case object ScaleRunSpecs

  case class RemoveLocks(runSpecId: Iterable[AbsolutePathId], lockVersion: Long)

  case class ScaleRunSpec(runSpecId: AbsolutePathId) extends Command {
    def answer: Event = RunSpecScaled(runSpecId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class CancelDeployment(plan: DeploymentPlan) extends Command {
    override def answer: Event = DeploymentFinished(plan)
  }

  sealed trait Event
  case class RunSpecScaled(runSpecId: AbsolutePathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable) extends Event
  case class DeploymentFinished(plan: DeploymentPlan) extends Event
  case class CommandFailed(cmd: Command, reason: Throwable) extends Event
}

class SchedulerActions(
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val killService: KillService)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends StrictLogging {

  // TODO move stuff below out of the scheduler

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

  /**
    * Make sure the runSpec is running the correct number of instances
    */
  // FIXME: extract computation into a function that can be easily tested
  def scale(runSpec: RunSpec): Future[Done] = async {
    logger.debug("Scale for run spec {}", runSpec)

    val instances = await(instanceTracker.specInstances(runSpec.id))

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runSpec, notSentencedAndRunning, toKillCount)
    }

    val targetCount = runSpec.instances

    val ScalingProposition(instancesToDecommission, instancesToStart) = ScalingProposition.propose(
      instances, Seq.empty, killToMeetConstraints, targetCount, runSpec.killSelection, runSpec.id)

    if (instancesToDecommission.nonEmpty) {
      logger.info(s"Adjusting goals for instances ${instancesToDecommission.map(_.instanceId)} (${GoalChangeReason.OverCapacity})")
      val instancesAreTerminal = KillStreamWatcher.watchForKilledTasks(instanceTracker.instanceUpdates, instances).runWith(Sink.ignore)

      // Race condition with line 421. The instances we loaded might not exist anymore, e.g. the agent
      // might have been removed and the instance expunged.
      val changeGoalsFuture = instancesToDecommission.map { i =>
        if (i.hasReservation) instanceTracker.setGoal(i.instanceId, Goal.Stopped, GoalChangeReason.OverCapacity)
        else instanceTracker.setGoal(i.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      }
      await(Future.sequence(changeGoalsFuture))
      await(instancesAreTerminal)

      Done
    }

    val toStart = instancesToStart
    if (toStart > 0) {
      await(launchQueue.add(runSpec, toStart))
    }

    if (instancesToDecommission.isEmpty && instancesToStart == 0) {
      logger.info(s"Already running ${runSpec.instances} instances of ${runSpec.id}. Not scaling.")
    }

    Done
  }

  def scale(runSpecId: AbsolutePathId): Future[Done] = async {
    val runSpec = await(runSpecById(runSpecId))
    runSpec match {
      case Some(runSpec) =>
        await(scale(runSpec))
      case _ =>
        logger.warn(s"RunSpec $runSpecId does not exist. Not scaling.")
        Done
    }
  }

  def runSpecById(id: AbsolutePathId): Future[Option[RunSpec]] = {
    groupRepository.root().map(_.runSpec(id))
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
