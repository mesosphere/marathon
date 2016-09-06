package mesosphere.marathon.upgrade

import java.net.URL

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.event.{ DeploymentStatus, DeploymentStepFailure, DeploymentStepSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFailed, DeploymentFinished, DeploymentStepInfo }
import mesosphere.mesos.Constraints
import org.apache.mesos.SchedulerDriver

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

private class DeploymentActor(
    deploymentManager: ActorRef,
    receiver: ActorRef,
    driver: SchedulerDriver,
    killService: TaskKillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    config: UpgradeConfig) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentActor._

  val steps = plan.steps.iterator
  var currentStep: Option[DeploymentStep] = None
  var currentStepNr: Int = 0

  override def preStart(): Unit = {
    self ! NextStep
  }

  override def postStop(): Unit = {
    deploymentManager ! DeploymentFinished(plan)
  }

  def receive: Receive = {
    case NextStep if steps.hasNext =>
      val step = steps.next()
      currentStepNr += 1
      currentStep = Some(step)
      deploymentManager ! DeploymentStepInfo(plan, currentStep.getOrElse(DeploymentStep(Nil)), currentStepNr)

      performStep(step) onComplete {
        case Success(_) => self ! NextStep
        case Failure(t) =>
          log.debug("Performing {} failed: {}", step, t)
          self ! Fail(t)
      }

    case NextStep =>
      // no more steps, we're done
      receiver ! DeploymentFinished(plan)
      context.stop(self)

    case Cancel(t) =>
      receiver ! DeploymentFailed(plan, t)
      context.stop(self)

    case Fail(t) =>
      log.debug("Deployment for {} failed: {}", plan, t)
      receiver ! DeploymentFailed(plan, t)
      context.stop(self)
  }

  // scalastyle:off
  def performStep(step: DeploymentStep): Future[Unit] = {
    if (step.actions.isEmpty) {
      Future.successful(())
    } else {
      val status = DeploymentStatus(plan, step)
      eventBus.publish(status)

      val futures = step.actions.map { action =>
        action match {
          case action: AppDeploymentAction =>
            healthCheckManager.addAllFor(action.app) // ensure health check actors are in place before tasks are launched
          case pod: PodDeploymentAction =>
          // TODO(PODS): do we need to do this for pods?
        }
        action match {
          case StartApplication(app, scaleTo) => startApp(app, scaleTo, status)
          case StartPod(pod, scaleTo) => startPod(pod, scaleTo, status)
          case ScaleApplication(app, scaleTo, toKill) => scaleApp(app, scaleTo, toKill, status)
          case ScalePod(pod, scaleTo, toKill) => scalePod(pod, scaleTo, toKill, status)
          case RestartApplication(app) => restartApp(app, status)
          case RestartPod(pod) => restartPod(pod, status)
          case StopApplication(app) => stopApp(app.copy(instances = 0))
          case StopPod(pod) => stopPod(pod.copy(instances = 0))
          case ResolveAppArtifacts(app, urls) => resolveAppArtifacts(app, urls)
          case ResolvePodArtifacts(pod, urls) => resolvePodArtifacts(pod, urls)
        }
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) => eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(_) => eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }
  // scalastyle:on

  def startApp(app: AppDefinition, scaleTo: Int, status: DeploymentStatus): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(
      AppStartActor.props(deploymentManager, status, driver, scheduler, launchQueue, taskTracker,
        eventBus, readinessCheckExecutor, app, scaleTo, promise)
    )
    promise.future
  }

  def startPod(pod: PodDefinition, scaleTo: Int, status: DeploymentStatus): Future[Unit] = {
    // TODO(PODS): implement start pod
    Future.failed(???)
  }

  def scaleApp(app: AppDefinition, scaleTo: Int,
    toKill: Option[Iterable[Instance]],
    status: DeploymentStatus): Future[Unit] = {
    val runningTasks = taskTracker.specInstancesLaunchedSync(app.id)
    def killToMeetConstraints(notSentencedAndRunning: Iterable[Instance], toKillCount: Int) =
      Constraints.selectTasksToKill(app, notSentencedAndRunning, toKillCount)

    val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
      runningTasks, toKill, killToMeetConstraints, scaleTo)

    def killTasksIfNeeded: Future[Unit] = tasksToKill.fold(Future.successful(())) { tasks =>
      killService.killTasks(tasks, TaskKillReason.ScalingApp).map(_ => ())
    }

    def startTasksIfNeeded: Future[Unit] = tasksToStart.fold(Future.successful(())) { _ =>
      val promise = Promise[Unit]()
      context.actorOf(
        TaskStartActor.props(deploymentManager, status, driver, scheduler, launchQueue, taskTracker, eventBus,
          readinessCheckExecutor, app, scaleTo, promise)
      )
      promise.future
    }

    killTasksIfNeeded.flatMap(_ => startTasksIfNeeded)
  }

  def scalePod(pod: PodDefinition, scaleTo: Int, toKill: Seq[Instance], status: DeploymentStatus): Future[Unit] = {
    // TODO(PODS): Implement scaling
    Future.failed(???)
  }

  def stopApp(app: AppDefinition): Future[Unit] = {
    val tasks = taskTracker.specInstancesLaunchedSync(app.id)
    // TODO: the launch queue is purged in stopApp, but it would make sense to do that before calling kill(tasks)
    killService.killTasks(tasks, TaskKillReason.DeletingApp).map(_ => ()).andThen {
      case Success(_) => scheduler.stopApp(app)
    }
  }

  def stopPod(pod: PodDefinition): Future[Unit] = {
    // TODO(PODS): implement stop pod
    Future.failed(???)
  }

  def restartApp(app: AppDefinition, status: DeploymentStatus): Future[Unit] = {
    if (app.instances == 0) {
      Future.successful(())
    } else {
      val promise = Promise[Unit]()
      context.actorOf(TaskReplaceActor.props(deploymentManager, status, driver, killService, launchQueue, taskTracker,
        eventBus, readinessCheckExecutor, app, promise))
      promise.future
    }
  }

  def restartPod(pod: PodDefinition, status: DeploymentStatus): Future[Unit] = {
    if (pod.instances == 0) {
      Future.successful(())
    } else {
      // TODO(PODS): implement restart pod
      Future.failed(???)
    }
  }

  def resolveAppArtifacts(app: AppDefinition, urls: Map[URL, String]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[ResolveArtifactsActor], app, urls, promise, storage))
    promise.future.map(_ => ())
  }

  def resolvePodArtifacts(pod: PodDefinition, urls: Map[URL, String]): Future[Unit] = {
    // TODO(PODS): implement resolve pod artifacts
    Promise[Unit]().future
  }
}

object DeploymentActor {
  case object NextStep
  case object Finished
  final case class Cancel(reason: Throwable)
  final case class Fail(reason: Throwable)
  final case class DeploymentActionInfo(plan: DeploymentPlan, step: DeploymentStep, action: DeploymentAction)

  // scalastyle:off parameter.number
  def props(
    deploymentManager: ActorRef,
    receiver: ActorRef,
    driver: SchedulerDriver,
    killService: TaskKillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    config: UpgradeConfig): Props = {
    // scalastyle:on parameter.number

    Props(new DeploymentActor(
      deploymentManager,
      receiver,
      driver,
      killService,
      scheduler,
      plan,
      taskTracker,
      launchQueue,
      storage,
      healthCheckManager,
      eventBus,
      readinessCheckExecutor,
      config
    ))
  }
}
