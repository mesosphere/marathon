package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.{ EventStream, LoggingReceive }
import akka.pattern.ask
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
import mesosphere.marathon.storage.repository.{ DeploymentRepository, GroupRepository, ReadOnlyAppRepository, ReadOnlyPodRepository }
import mesosphere.marathon.stream._
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan, ScalingProposition }
import mesosphere.mesos.Constraints
import org.apache.mesos
import org.apache.mesos.Protos.{ Status, TaskState }
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class LockingFailedException(msg: String) extends Exception(msg)

class MarathonSchedulerActor private (
  createSchedulerActions: ActorRef => SchedulerActions,
  deploymentManagerProps: SchedulerActions => Props,
  historyActorProps: Props,
  deploymentRepository: DeploymentRepository,
  healthCheckManager: HealthCheckManager,
  killService: KillService,
  launchQueue: LaunchQueue,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  electionService: ElectionService,
  eventBus: EventStream,
  cancellationTimeout: FiniteDuration = 1.minute)(implicit val mat: Materializer) extends Actor
    with ActorLogging with Stash {
  import context.dispatcher
  import mesosphere.marathon.MarathonSchedulerActor._

  var lockedRunSpecs = Set.empty[PathId]
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
      deploymentRepository.all().runWith(Sink.seq).onComplete {
        case Success(deployments) => self ! RecoverDeployments(deployments)
        case Failure(_) => self ! RecoverDeployments(Nil)
      }

    case RecoverDeployments(deployments) =>
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

  def started: Receive = LoggingReceive.withLabel("started")(sharedHandlers orElse {
    case LocalLeadershipEvent.Standby =>
      log.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      deploymentManager ! StopAllDeployments
      lockedRunSpecs = Set.empty
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
      val origSender = sender()
      withLockFor(runSpecId) {
        val res = schedulerActions.scale(runSpecId)

        if (origSender != context.system.deadLetters)
          res.sendAnswer(origSender, cmd)

        res andThen {
          case _ => self ! cmd.answer // unlock app
        }
      }

    case cmd: CancelDeployment =>
      deploymentManager forward cmd

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(runSpecId, tasks) =>
      val origSender = sender()
      @SuppressWarnings(Array("all")) /* async/await */
      def killTasks(): Unit = {
        val res = async { // linter:ignore UnnecessaryElseBranch
          await(killService.killInstances(tasks, KillReason.KillingTasksViaApi))
          val runSpec = await(schedulerActions.runSpecById(runSpecId))
          runSpec.foreach(schedulerActions.scale)
        }

        res onComplete { _ =>
          self ! cmd.answer // unlock app
        }

        res.sendAnswer(origSender, cmd)
      }
      withLockFor(runSpecId) { killTasks() }
  })

  /**
    * handlers for messages that unlock run specs and to retrieve running deployments
    */
  def sharedHandlers: Receive = {
    case DeploymentFinished(plan) =>
      lockedRunSpecs --= plan.affectedRunSpecIds
      deploymentSuccess(plan)

    case DeploymentManager.DeploymentFailed(plan, reason) =>
      lockedRunSpecs --= plan.affectedRunSpecIds
      deploymentFailed(plan, reason)

    case RunSpecScaled(id) => lockedRunSpecs -= id

    case TasksKilled(runSpecId, _) => lockedRunSpecs -= runSpecId

    case RetrieveRunningDeployments =>
      deploymentManager forward RetrieveRunningDeployments
  }

  /**
    * Waits for all the rnunSpecs affected by @plan to be unlocked
    * and starts @plan. If it receives a CancellationTimeoutExceeded
    * message, it will mark the deployment as failed and go into
    * the started state.
    *
    * @param plan The deployment plan we are trying to execute.
    * @param origSender The original sender of the Deploy message.
    * @return
    */
  @SuppressWarnings(Array("all")) // async/await
  def awaitCancellation(plan: DeploymentPlan, origSender: ActorRef, cancellationHandler: Cancellable): Receive =
    sharedHandlers.andThen[Unit] { _ =>
      if (tryDeploy(plan, origSender)) {
        cancellationHandler.cancel()
      }
    } orElse {
      case CancellationTimeoutExceeded =>
        val reason = new TimeoutException("Exceeded timeout for canceling conflicting deployments.")
        async { // linter:ignore UnnecessaryElseBranch
          await(deploymentFailed(plan, reason))
          origSender ! CommandFailed(Deploy(plan, force = true), reason)
          unstashAll()
          context.become(started)
        }
      case _ => stash()
    }

  /**
    * If all required runSpecs are unlocked, start the deployment,
    * unstash all messages and put actor in started state
    *
    * @param plan The deployment plan that has been sent with force=true
    * @param origSender The original sender of the deployment
    */
  def tryDeploy(plan: DeploymentPlan, origSender: ActorRef): Boolean = {
    val affectedRunSpecs = plan.affectedRunSpecIds
    if (!lockedRunSpecs.exists(affectedRunSpecs)) {
      deploy(origSender, Deploy(plan, force = false))
      unstashAll()
      context.become(started)
      true
    } else {
      false
    }
  }

  /**
    * Tries to acquire the lock for the given runSpecIds.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an LockingFailedException.
    */
  def withLockFor[A](runSpecIds: Set[PathId])(f: => A): Try[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    val conflicts = lockedRunSpecs intersect runSpecIds
    if (conflicts.isEmpty) {
      lockedRunSpecs ++= runSpecIds
      Try(f)
    } else {
      Failure(new LockingFailedException("Failed to acquire locks."))
    }
  }

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
    val ids = plan.affectedRunSpecIds

    val res = withLockFor(ids) {
      deploy(driver, plan)
    }

    res match {
      case Success(f) =>
        f.map(_ => if (origSender != Actor.noSender) origSender ! cmd.answer)
      case Failure(e: LockingFailedException) if cmd.force =>
        deploymentManager ! CancelConflictingDeployments(plan)
        val cancellationHandler = context.system.scheduler.scheduleOnce(
          cancellationTimeout,
          self,
          CancellationTimeoutExceeded)

        context.become(awaitCancellation(plan, origSender, cancellationHandler))
      case Failure(e: LockingFailedException) =>
        deploymentManager.ask(RetrieveRunningDeployments)(2.seconds)
          .mapTo[RunningDeployments]
          .foreach {
            case RunningDeployments(plans) =>
              def intersectsWithNewPlan(existingPlan: DeploymentPlan): Boolean = {
                existingPlan.affectedRunSpecIds.intersect(plan.affectedRunSpecIds).nonEmpty
              }
              val relatedDeploymentIds: Seq[String] = plans.collect {
                case DeploymentStepInfo(p, _, _, _) if intersectsWithNewPlan(p) => p.id
              }
              origSender ! CommandFailed(cmd, AppLockedException(relatedDeploymentIds))
          }
    }
  }

  def deploy(driver: SchedulerDriver, plan: DeploymentPlan): Future[Unit] = {
    deploymentRepository.store(plan).map { _ =>
      deploymentManager ! PerformDeployment(driver, plan)
    }
  }

  def deploymentSuccess(plan: DeploymentPlan): Future[Unit] = {
    log.info(s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} successful")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
    deploymentRepository.delete(plan.id).map(_ => ())
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Future[Unit] = {
    log.error(reason, s"Deployment ${plan.id}:${plan.version} of ${plan.target.id} failed")
    plan.affectedRunSpecIds.foreach(runSpecId => launchQueue.purge(runSpecId))
    eventBus.publish(DeploymentFailed(plan.id, plan))
    reason match {
      case _: DeploymentCanceledException =>
        deploymentRepository.delete(plan.id).map(_ => ())
      case _ =>
        Future.successful(())
    }
  }
}

object MarathonSchedulerActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    createSchedulerActions: ActorRef => SchedulerActions,
    deploymentManagerProps: SchedulerActions => Props,
    historyActorProps: Props,
    deploymentRepository: DeploymentRepository,
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
      deploymentRepository,
      healthCheckManager,
      killService,
      launchQueue,
      marathonSchedulerDriverHolder,
      electionService,
      eventBus
    ))
  }

  case class RecoverDeployments(deployments: Seq[DeploymentPlan])

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

  implicit class AnswerOps[A](val f: Future[A]) extends AnyVal {
    def sendAnswer(receiver: ActorRef, cmd: Command)(implicit ec: ExecutionContext): Future[A] = {
      f onComplete {
        case Success(_) =>
          receiver ! cmd.answer

        case Failure(t) =>
          receiver ! CommandFailed(cmd, t)
      }

      f
    }
  }
}

class SchedulerActions(
    appRepository: ReadOnlyAppRepository,
    podRepository: ReadOnlyPodRepository,
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val schedulerActor: ActorRef,
    val killService: KillService)(implicit ec: ExecutionContext, mat: Materializer) {

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

  def scaleRunSpec(): Future[Unit] = {
    appRepository.ids().concat(podRepository.ids()).runWith(Sink.set).andThen {
      case Success(ids) => for (id <- ids) schedulerActor ! ScaleRunSpec(id)
      case Failure(t) => log.warn("Failed to get task names", t)
    }.map(_ => ())
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
    appRepository.ids().concat(podRepository.ids()).runWith(Sink.set).flatMap { runSpecIds =>
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

  @SuppressWarnings(Array("all")) // async/await
  def reconcileHealthChecks(): Unit = {
    async { // linter:ignore UnnecessaryElseBranch
      val rootGroup = await(groupRepository.root())
      val runSpecs = rootGroup.transitiveRunSpecsById.keys
      runSpecs.foreach(healthCheckManager.reconcileWith)
    }
  }

  /**
    * Make sure the runSpec is running the correct number of instances
    */
  // FIXME: extract computation into a function that can be easily tested
  def scale(runSpec: RunSpec): Unit = {

    val runningTasks = instanceTracker.specInstancesSync(runSpec.id).filter(_.state.condition.isActive)

    def killToMeetConstraints(notSentencedAndRunning: Seq[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runSpec, notSentencedAndRunning, toKillCount)
    }

    val targetCount = runSpec.instances

    val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
      runningTasks, None, killToMeetConstraints, targetCount, runSpec.unreachableStrategy.killSelection)

    tasksToKill.foreach { tasks: Seq[Instance] =>
      log.info(s"Scaling ${runSpec.id} from ${runningTasks.size} down to $targetCount instances")

      launchQueue.purge(runSpec.id)

      log.info("Killing tasks {}", tasks.map(_.instanceId))
      killService.killInstances(tasks, KillReason.OverCapacity)
    }

    tasksToStart.foreach { toQueue: Int =>
      log.info(s"Need to scale ${runSpec.id} from ${runningTasks.size} up to $targetCount instances")

      if (toQueue > 0) {
        log.info(s"Queueing $toQueue new tasks for ${runSpec.id}")
        launchQueue.add(runSpec, toQueue)
      } else {
        log.info(s"Already queued or started ${runningTasks.size} tasks for ${runSpec.id}. Not scaling.")
      }
    }

    if (tasksToKill.isEmpty && tasksToStart.isEmpty) {
      log.info(s"Already running ${runSpec.instances} instances of ${runSpec.id}. Not scaling.")
    }
  }

  def scale(runSpecId: PathId): Future[Unit] = {
    runSpecById(runSpecId).map {
      case Some(runSpec) => scale(runSpec)
      case _ => log.warn(s"RunSpec $runSpecId does not exist. Not scaling.")
    }
  }

  def runSpecById(id: PathId): Future[Option[RunSpec]] = {
    appRepository.get(id).flatMap {
      case Some(app) => Future.successful(Some(app))
      case None => podRepository.get(id)
    }
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
