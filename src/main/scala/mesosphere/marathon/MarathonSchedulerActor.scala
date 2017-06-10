package mesosphere.marathon

import akka.Done
import akka.actor._
import akka.pattern.pipe
import akka.event.{ EventStream, LoggingReceive }
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.{ DeploymentManager, DeploymentPlan, ScalingProposition }
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event.{ AppTerminatedEvent, DeploymentSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ PathId, RunSpec }
import mesosphere.marathon.storage.repository.{ DeploymentRepository, GroupRepository }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.util._
import mesosphere.mesos.Constraints
import org.apache.mesos
import org.apache.mesos.Protos.{ Status, TaskState }
import org.apache.mesos.SchedulerDriver

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

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
  electionService: ElectionService,
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
  val lockedRunSpecs = collection.mutable.Map[PathId, Int]().withDefaultValue(0)
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[Status]] = None

  override def preStart(): Unit = {
    historyActor = context.actorOf(historyActorProps, "HistoryActor")
    electionService.subscribe(self)
  }

  override def postStop(): Unit = {
    electionService.unsubscribe(self)
  }

  def receive: Receive = suspended

  def suspended: Receive = LoggingReceive.withLabel("suspended"){
    case LocalLeadershipEvent.ElectedAsLeader =>
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

    case LocalLeadershipEvent.Standby =>
    // ignored
    // FIXME: When we get this while recovering deployments, we become active anyway
    // and drop this message.

    case _ => stash()
  }

  def started: Receive = LoggingReceive.withLabel("started") {
    case LocalLeadershipEvent.Standby =>
      logger.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      lockedRunSpecs.clear()
      context.become(suspended)

    case LocalLeadershipEvent.ElectedAsLeader => // ignore

    case ReconcileTasks =>
      import akka.pattern.pipe
      import context.dispatcher
      val reconcileFuture = activeReconciliation match {
        case None =>
          logger.info("initiate task reconciliation")
          val newFuture = schedulerActions.reconcileTasks(driver)
          activeReconciliation = Some(newFuture)
          newFuture.onFailure {
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

      withLockFor(Set(runSpecId)) {
        val result: Future[Event] = schedulerActions.scale(runSpecId).map { _ =>
          self ! cmd.answer
          cmd.answer
        }.recover { case ex => CommandFailed(cmd, ex) }
        if (sender != context.system.deadLetters)
          result.pipeTo(sender)
      } match {
        case None =>
          // ScaleRunSpec is not a user initiated command
          logger.debug(s"Did not try to scale run spec ${runSpecId}; it is locked")
        case _ =>
      }

    case cmd @ CancelDeployment(plan) =>
      deploymentManager.cancel(plan).onComplete{
        case Success(d) => self ! cmd.answer
        case Failure(e) => logger.error(s"Failed to cancel a deployment ${plan.id} due to: ", e)
      }

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(runSpecId, tasks) =>
      @SuppressWarnings(Array("all")) /* async/await */
      def killTasks(): Future[Event] = {
        logger.debug("Received kill tasks {} of run spec {}", tasks, runSpecId)
        async {
          await(killService.killInstances(tasks, KillReason.KillingTasksViaApi))
          await(schedulerActions.scale(runSpecId))
          self ! cmd.answer
          cmd.answer
        }.recover {
          case t: Throwable =>
            CommandFailed(cmd, t)
        }
      }

      withLockFor(Set(runSpecId)) {
        killTasks().pipeTo(sender)
      } match {
        case None =>
          // KillTasks is user initiated. If we don't process it, then we should make it obvious as to why.
          logger.warn(
            s"Could not acquire lock while killing tasks ${tasks.map(_.instanceId).toList} for ${runSpecId}")
        case _ =>
      }

    case DeploymentFinished(plan) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentSuccess(plan)

    case DeploymentFailed(plan, reason) =>
      removeLocks(plan.affectedRunSpecIds)
      deploymentFailed(plan, reason)

    case RunSpecScaled(id) => removeLock(id)

    case TasksKilled(runSpecId, _) => removeLock(runSpecId)

    case msg => logger.warn(s"Received unexpected message from ${sender()}: $msg")
  }

  def scaleRunSpecs(): Unit = {
    groupRepository.root().foreach { root =>
      root.transitiveRunSpecs.foreach(spec => self ! ScaleRunSpec(spec.id))
    }
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

  // there has to be a better way...
  @SuppressWarnings(Array("OptionGet"))
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
    logger.info(s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} finished")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    logger.error(s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} failed", reason)
    Future.sequence(plan.affectedRunSpecIds.map(launchQueue.asyncPurge))
      .recover { case NonFatal(error) => logger.warn(s"Error during async purge: planId=${plan.id}", error); Done }
      .foreach { _ => eventBus.publish(core.event.DeploymentFailed(plan.id, plan)) }
  }
}

object MarathonSchedulerActor {
  @SuppressWarnings(Array("MaxParameters"))
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
    electionService: ElectionService,
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

  case class CancelDeployment(plan: DeploymentPlan) extends Command {
    override def answer: Event = DeploymentFinished(plan)
  }

  sealed trait Event
  case class RunSpecScaled(runSpecId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable) extends Event
  case class DeploymentFinished(plan: DeploymentPlan) extends Event
  case class TasksKilled(runSpecId: PathId, taskIds: Seq[Instance.Id]) extends Event
  case class CommandFailed(cmd: Command, reason: Throwable) extends Event
}

class SchedulerActions(
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val killService: KillService)(implicit ec: ExecutionContext) extends StrictLogging {

  // TODO move stuff below out of the scheduler

  def startRunSpec(runSpec: RunSpec): Future[Done] = {
    logger.info(s"Starting runSpec ${runSpec.id}")
    scale(runSpec)
  }

  @SuppressWarnings(Array("all")) // async/await
  def stopRunSpec(runSpec: RunSpec): Future[Done] = {
    healthCheckManager.removeAllFor(runSpec.id)

    logger.info(s"Stopping runSpec ${runSpec.id}")
    async {
      val tasks = await(instanceTracker.specInstances(runSpec.id))

      tasks.foreach { instance =>
        if (instance.isLaunched) {
          logger.info("Killing {}", instance.instanceId)
          killService.killInstance(instance, KillReason.DeletingApp)
        }
      }
      await(launchQueue.asyncPurge(runSpec.id))
      Done
    }.recover {
      case NonFatal(error) => logger.warn(s"Error in stopping runSpec ${runSpec.id}", error); Done
    }.map { _ =>
      launchQueue.resetDelay(runSpec)

      // The tasks will be removed from the InstanceTracker when their termination
      // was confirmed by Mesos via a task update.
      eventBus.publish(AppTerminatedEvent(runSpec.id))
      Done
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
  @SuppressWarnings(Array("all")) // async/await
  def reconcileTasks(driver: SchedulerDriver): Future[Status] = async {
    val root = await(groupRepository.root())

    val runSpecIds = root.transitiveRunSpecsById.keySet
    val instances = await(instanceTracker.instancesBySpec())

    val knownTaskStatuses = runSpecIds.flatMap { runSpecId =>
      TaskStatusCollector.collectTaskStatusFor(instances.specInstances(runSpecId))
    }

    (instances.allSpecIdsWithInstances -- runSpecIds).foreach { unknownId =>
      logger.warn(
        s"RunSpec $unknownId exists in InstanceTracker, but not store. " +
          "The run spec was likely terminated. Will now expunge."
      )
      instances.specInstances(unknownId).foreach { orphanTask =>
        logger.info(s"Killing ${orphanTask.instanceId}")
        killService.killInstance(orphanTask, KillReason.Orphaned)
      }
    }

    logger.info("Requesting task reconciliation with the Mesos master")
    logger.debug(s"Tasks to reconcile: $knownTaskStatuses")
    if (knownTaskStatuses.nonEmpty) driver.reconcileTasks(knownTaskStatuses.asJavaCollection)

    // in addition to the known statuses send an empty list to get the unknown
    driver.reconcileTasks(java.util.Arrays.asList())
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
    logger.debug("Scale for run spec {}", runSpec)

    val runningInstances = await(instanceTracker.specInstances(runSpec.id)).filter(_.state.condition.isActive)

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runSpec, notSentencedAndRunning, toKillCount)
    }

    val targetCount = runSpec.instances

    val ScalingProposition(instancesToKill, instancesToStart) = ScalingProposition.propose(
      runningInstances, None, killToMeetConstraints, targetCount, runSpec.killSelection)

    instancesToKill.foreach { instances: Seq[Instance] =>
      logger.info(s"Scaling ${runSpec.id} from ${runningInstances.size} down to $targetCount instances")

      launchQueue.asyncPurge(runSpec.id)
        .recover {
          case NonFatal(e) =>
            logger.warn("Async purge failed: {}", e)
            Done
        }.foreach { _ =>
          logger.info(s"Killing instances ${instances.map(_.instanceId)}")
          killService.killInstances(instances, KillReason.OverCapacity)
        }

    }

    instancesToStart.foreach { toStart: Int =>
      logger.info(s"Need to scale ${runSpec.id} from ${runningInstances.size} up to $targetCount instances")
      val leftToLaunch = launchQueue.get(runSpec.id).fold(0)(_.instancesLeftToLaunch)
      val toAdd = toStart - leftToLaunch

      if (toAdd > 0) {
        logger.info(s"Queueing $toAdd new instances for ${runSpec.id} to the already $leftToLaunch queued ones")
        launchQueue.add(runSpec, toAdd)
      } else {
        logger.info(s"Already queued or started ${runningInstances.size} instances for ${runSpec.id}. Not scaling.")
      }
    }

    if (instancesToKill.isEmpty && instancesToStart.isEmpty) {
      logger.info(s"Already running ${runSpec.instances} instances of ${runSpec.id}. Not scaling.")
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
        logger.warn(s"RunSpec $runSpecId does not exist. Not scaling.")
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
