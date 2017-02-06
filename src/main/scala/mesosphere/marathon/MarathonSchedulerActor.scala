package mesosphere.marathon

import akka.Done
import akka.actor._
import akka.event.{ EventStream, LoggingReceive }
import akka.stream.Materializer
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event.{ AppTerminatedEvent, DeploymentFailed, DeploymentSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ PathId, RunSpec }
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan, ScalingProposition }
import mesosphere.marathon.util._
import mesosphere.mesos.Constraints
import org.apache.mesos
import org.apache.mesos.Protos.{ Status, TaskState }
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class LockingFailedException(msg: String) extends Exception(msg)

class MarathonSchedulerActor private (
  createSchedulerActions: ActorRef => SchedulerActions,
  deploymentManagerProps: SchedulerActions => Props,
  historyActorProps: Props,
  healthCheckManager: HealthCheckManager,
  killService: KillService,
  launchQueue: LaunchQueue,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  electionService: ElectionService,
  eventBus: EventStream)(implicit val mat: Materializer) extends Actor
    with ActorLogging with Stash {
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
  // TODO (AD): DeploymentManager has already all the information about running deployments.
  // MarathonSchedulerActor should only save the locks resulting from scale and kill operations,
  // asking DeploymentManager for deployment locks.
  val lockedRunSpecs = collection.mutable.Map[PathId, Int]().withDefaultValue(0)
  var schedulerActions: SchedulerActions = _
  var deploymentManager: ActorRef = _
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[Status]] = None

  override def preStart(): Unit = {
    schedulerActions = createSchedulerActions(self)
    deploymentManager = context.actorOf(deploymentManagerProps(schedulerActions), "DeploymentManager")
    historyActor = context.actorOf(historyActorProps, "HistoryActor")

    electionService.subscribe(self)
  }

  override def postStop(): Unit = {
    electionService.unsubscribe(self)
  }

  def receive: Receive = suspended

  def suspended: Receive = LoggingReceive.withLabel("suspended"){
    case LocalLeadershipEvent.ElectedAsLeader =>
      log.info("Starting scheduler actor")
      deploymentManager ! LoadDeploymentsOnLeaderElection

    case LoadedDeploymentsOnLeaderElection(deployments) =>
      deployments.foreach { plan =>
        log.info(s"Recovering deployment:\n$plan")
        deploy(context.system.deadLetters, Deploy(plan, force = false))
      }

      log.info("Scheduler actor ready")
      unstashAll()
      context.become(started)
      self ! ReconcileHealthChecks

    case LocalLeadershipEvent.Standby =>
    // ignored
    // FIXME: When we get this while recovering deployments, we become active anyway
    // and drop this message.

    case _ => stash()
  }

  def started: Receive = LoggingReceive.withLabel("started") {
    case LocalLeadershipEvent.Standby =>
      log.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      deploymentManager ! ShutdownDeployments
      lockedRunSpecs.clear()
      context.become(suspended)

    case LocalLeadershipEvent.ElectedAsLeader => // ignore

    case ReconcileTasks =>
      import akka.pattern.pipe
      import context.dispatcher
      val reconcileFuture = activeReconciliation match {
        case None =>
          log.info("initiate task reconciliation")
          val newFuture = schedulerActions.reconcileTasks(driver)
          activeReconciliation = Some(newFuture)
          newFuture.onFailure {
            case NonFatal(e) => log.error(e, "error while reconciling tasks")
          }
          newFuture
            // the self notification MUST happen before informing the initiator
            // if we want to ensure that we trigger a new reconciliation for
            // the first call after the last ReconcileTasks.answer has been received.
            .andThen { case _ => self ! ReconcileFinished }
        case Some(active) =>
          log.info("task reconciliation still active, reusing result")
          active
      }
      reconcileFuture.map(_ => ReconcileTasks.answer).pipeTo(sender)

    case ReconcileFinished =>
      log.info("task reconciliation has finished")
      activeReconciliation = None

    case ReconcileHealthChecks =>
      schedulerActions.reconcileHealthChecks()

    case ScaleRunSpecs => schedulerActions.scaleRunSpec()

    case cmd @ ScaleRunSpec(runSpecId) =>
      log.debug("Receive scale run spec for {}", runSpecId)
      val origSender = sender()
      @SuppressWarnings(Array("all")) /* async/await */
      def scaleAndAnswer(): Done = {
        val res: Future[Done] = async {
          await(schedulerActions.scale(runSpecId))
          self ! cmd.answer
          Done
        }

        if (origSender != context.system.deadLetters) {
          res.asTry.onComplete {
            case Success(_) => origSender ! cmd.answer
            case Failure(t) => origSender ! CommandFailed(cmd, t)
          }
        }
        Done
      }
      withLockFor(runSpecId) { scaleAndAnswer() }

    case cmd: CancelDeployment =>
      deploymentManager forward cmd

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(runSpecId, tasks) =>
      val origSender = sender()
      @SuppressWarnings(Array("all")) /* async/await */
      def killTasks(): Done = {
        log.debug("Received kill tasks {} of run spec {}", tasks, runSpecId)
        val res: Future[Done] = async {
          await(killService.killInstances(tasks, KillReason.KillingTasksViaApi))
          await(schedulerActions.scale(runSpecId))
          self ! cmd.answer
          Done
        }

        res.asTry.onComplete {
          case Success(_) => origSender ! cmd.answer
          case Failure(t) => origSender ! CommandFailed(cmd, t)
        }
        Done
      }
      withLockFor(runSpecId) { killTasks() }

    case DeploymentFinished(plan) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentSuccess(plan)

    case DeploymentManager.DeploymentFailed(plan, reason) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentFailed(plan, reason)

    case RunSpecScaled(id) => removeLock(id)

    case TasksKilled(runSpecId, _) => removeLock(runSpecId)

    case RetrieveRunningDeployments =>
      deploymentManager forward RetrieveRunningDeployments
  }

  /**
    * Tries to acquire the lock for the given runSpecIds.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an LockingFailedException.
    */
  def withLockFor[A](runSpecIds: Set[PathId])(f: => A): Try[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    if (noConflictsWith(runSpecIds)) {
      addLocks(runSpecIds)
      Try(f)
    } else {
      Failure(new LockingFailedException("Failed to acquire locks."))
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
    }
  }

  def addLocks(runSpecIds: Set[PathId]): Unit = runSpecIds.foreach(addLock)
  def addLock(runSpecId: PathId): Unit = lockedRunSpecs(runSpecId) += 1

  /**
    * Tries to acquire the lock for the given runSpecId.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an AppLockedException.
    */
  def withLockFor[A](runSpecId: PathId)(f: => A): Try[A] =
    withLockFor(Set(runSpecId))(f)

  // there has to be a better way...
  @SuppressWarnings(Array("OptionGet"))
  def driver: SchedulerDriver = marathonSchedulerDriverHolder.driver.get

  def deploy(origSender: ActorRef, cmd: Deploy): Unit = {
    val plan = cmd.plan
    val runSpecIds = plan.affectedRunSpecIds

    // If there are no conflicting locks or the deployment is forced we lock passed runSpecIds.
    // Afterwards the deployment plan is sent to DeploymentManager. It will take care of cancelling
    // conflicting deployments, scheduling new one or (if there were conflicts but the deployment
    // is not forced) send to the original sender and AppLockedException with conflicting deployments.
    //
    // If a deployment is forced (and there exists an old one):
    // - the new deployment will be started
    // - the old deployment will be cancelled and release all claimed locks
    // - only in this case, one RunSpec can have 2 locks
    if (noConflictsWith(runSpecIds) || cmd.force) {
      addLocks(runSpecIds)
    }
    deploymentManager ! StartDeployment(plan, origSender, cmd.force)
  }

  def deploymentSuccess(plan: DeploymentPlan): Unit = {
    log.info(s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} finished")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    log.error(reason, s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} failed")
    plan.affectedRunSpecIds.foreach(runSpecId => launchQueue.purge(runSpecId))
    eventBus.publish(DeploymentFailed(plan.id, plan))
  }
}

object MarathonSchedulerActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    createSchedulerActions: ActorRef => SchedulerActions,
    deploymentManagerProps: SchedulerActions => Props,
    historyActorProps: Props,
    healthCheckManager: HealthCheckManager,
    killService: KillService,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    electionService: ElectionService,
    eventBus: EventStream)(implicit mat: Materializer): Props = {
    Props(new MarathonSchedulerActor(
      createSchedulerActions,
      deploymentManagerProps,
      historyActorProps,
      healthCheckManager,
      killService,
      launchQueue,
      marathonSchedulerDriverHolder,
      electionService,
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

  case class ScaleRunSpec(runSpecId: PathId) extends Command {
    def answer: Event = RunSpecScaled(runSpecId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class KillTasks(runSpecId: PathId, tasks: Seq[Instance]) extends Command {
    def answer: Event = TasksKilled(runSpecId, tasks.map(_.instanceId))
  }

  case object RetrieveRunningDeployments

  sealed trait Event
  case class RunSpecScaled(runSpecId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class TasksKilled(runSpecId: PathId, taskIds: Seq[Instance.Id]) extends Event

  case class RunningDeployments(plans: Seq[DeploymentStepInfo])

  case class CommandFailed(cmd: Command, reason: Throwable) extends Event

  case object CancellationTimeoutExceeded
}

class SchedulerActions(
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val schedulerActor: ActorRef,
    val killService: KillService)(implicit ec: ExecutionContext) {

  private[this] val log = LoggerFactory.getLogger(getClass)

  // TODO move stuff below out of the scheduler

  def startRunSpec(runSpec: RunSpec): Unit = {
    log.info(s"Starting runSpec ${runSpec.id}")
    scale(runSpec)
  }

  def stopRunSpec(runSpec: RunSpec): Future[_] = {
    healthCheckManager.removeAllFor(runSpec.id)

    log.info(s"Stopping runSpec ${runSpec.id}")
    instanceTracker.specInstances(runSpec.id).map { tasks =>
      tasks.foreach {
        instance =>
          if (instance.isLaunched) {
            log.info("Killing {}", instance.instanceId)
            killService.killInstance(instance, KillReason.DeletingApp)
          }
      }
      launchQueue.purge(runSpec.id)
      launchQueue.resetDelay(runSpec)

      // The tasks will be removed from the InstanceTracker when their termination
      // was confirmed by Mesos via a task update.

      eventBus.publish(AppTerminatedEvent(runSpec.id))
    }
  }

  def scaleRunSpec(): Unit = {
    groupRepository.root().foreach { root =>
      root.transitiveRunSpecs.foreach(spec => schedulerActor ! ScaleRunSpec(spec.id))
    }
  }

  /**
    * Make sure all runSpecs are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    *
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Future[Status] = {
    groupRepository.root().flatMap { root =>
      val runSpecIds = root.transitiveRunSpecsById.keySet
      instanceTracker.instancesBySpec().map { instances =>
        val knownTaskStatuses = runSpecIds.flatMap { runSpecId =>
          TaskStatusCollector.collectTaskStatusFor(instances.specInstances(runSpecId))
        }

        (instances.allSpecIdsWithInstances -- runSpecIds).foreach { unknownId =>
          log.warn(
            s"RunSpec $unknownId exists in InstanceTracker, but not store. " +
              "The run spec was likely terminated. Will now expunge."
          )
          instances.specInstances(unknownId).foreach { orphanTask =>
            log.info(s"Killing ${orphanTask.instanceId}")
            killService.killInstance(orphanTask, KillReason.Orphaned)
          }
        }

        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $knownTaskStatuses")
        if (knownTaskStatuses.nonEmpty)
          driver.reconcileTasks(knownTaskStatuses)

        // in addition to the known statuses send an empty list to get the unknown
        driver.reconcileTasks(java.util.Arrays.asList())
      }
    }
  }

  def reconcileHealthChecks(): Unit = {
    groupRepository.root().flatMap { rootGroup =>
      healthCheckManager.reconcile(rootGroup.transitiveAppsById.valuesIterator.to[Seq])
    }
  }

  /**
    * Make sure the runSpec is running the correct number of instances
    */
  // FIXME: extract computation into a function that can be easily tested
  @SuppressWarnings(Array("all")) // async/await
  def scale(runSpec: RunSpec): Future[Done] = async {
    log.debug("Scale for run spec {}", runSpec)

    val runningInstances = await(instanceTracker.specInstances(runSpec.id)).filter(_.state.condition.isActive)

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runSpec, notSentencedAndRunning, toKillCount)
    }

    val targetCount = runSpec.instances

    val ScalingProposition(instancesToKill, instancesToStart) = ScalingProposition.propose(
      runningInstances, None, killToMeetConstraints, targetCount, runSpec.killSelection)

    instancesToKill.foreach { instances: Seq[Instance] =>
      log.info(s"Scaling ${runSpec.id} from ${runningInstances.size} down to $targetCount instances")

      launchQueue.purge(runSpec.id)

      log.info("Killing instances {}", instances.map(_.instanceId))
      killService.killInstances(instances, KillReason.OverCapacity)
    }

    instancesToStart.foreach { toStart: Int =>
      log.info(s"Need to scale ${runSpec.id} from ${runningInstances.size} up to $targetCount instances")
      val leftToLaunch = launchQueue.get(runSpec.id).fold(0)(_.instancesLeftToLaunch)
      val toAdd = toStart - leftToLaunch

      if (toAdd > 0) {
        log.info(s"Queueing $toAdd new instances for ${runSpec.id} to the already $leftToLaunch queued ones")
        launchQueue.add(runSpec, toAdd)
      } else {
        log.info(s"Already queued or started ${runningInstances.size} instances for ${runSpec.id}. Not scaling.")
      }
    }

    if (instancesToKill.isEmpty && instancesToStart.isEmpty) {
      log.info(s"Already running ${runSpec.instances} instances of ${runSpec.id}. Not scaling.")
    }

    Done
  }

  @SuppressWarnings(Array("all")) // async/await
  def scale(runSpecId: PathId): Future[Done] = async {
    val runSpec = await(runSpecById(runSpecId))
    runSpec match {
      case Some(runSpec) =>
        await(scale(runSpec))
      case _ =>
        log.warn(s"RunSpec $runSpecId does not exist. Not scaling.")
        Done
    }
  }

  def runSpecById(id: PathId): Future[Option[RunSpec]] = {
    groupRepository.root().map(_.transitiveRunSpecsById.get(id))
  }
}

/**
  * Provides means to collect Mesos TaskStatus information for reconciliation.
  */
object TaskStatusCollector {
  def collectTaskStatusFor(instances: Seq[Instance]): Seq[mesos.Protos.TaskStatus] = {
    instances.flatMap { instance =>
      instance.tasksMap.values.withFilter(task => !task.isTerminal && !task.isReserved).map { task =>
        task.status.mesosStatus.getOrElse(initialMesosStatusFor(task, instance.agentInfo))
      }
    }(collection.breakOut)
  }

  /**
    * If a task was started but Marathon never received a status update for it, it will not have a
    * Mesos TaskStatus attached. In order to reconcile the state of this task, we need to create a
    * TaskStatus and fill it with the required information.
    */
  private[this] def initialMesosStatusFor(task: Task, agentInfo: AgentInfo): mesos.Protos.TaskStatus = {
    val taskStatusBuilder = mesos.Protos.TaskStatus.newBuilder
      // in fact we haven't received a status update for these yet, we just pretend it's staging
      .setState(TaskState.TASK_STAGING)
      .setTaskId(task.taskId.mesosTaskId)

    agentInfo.agentId.foreach { agentId =>
      taskStatusBuilder.setSlaveId(mesos.Protos.SlaveID.newBuilder().setValue(agentId))
    }

    taskStatusBuilder.build()
  }

}
