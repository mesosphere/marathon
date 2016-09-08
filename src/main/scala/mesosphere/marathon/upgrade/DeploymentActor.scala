package mesosphere.marathon.upgrade

import java.net.URL

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.event.{ DeploymentStatus, DeploymentStepFailure, DeploymentStepSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
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
    taskTracker: TaskTracker,
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
        case Failure(t) => self ! Fail(t)
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

  def performStep(step: DeploymentStep): Future[Unit] = {
    if (step.actions.isEmpty) {
      Future.successful(())
    } else {
      val status = DeploymentStatus(plan, step)
      eventBus.publish(status)

      val futures = step.actions.map { action =>
        // ensure health check actors are in place before tasks are launched
        healthCheckManager.addAllFor(action.app, Seq.empty)
        action match {
          case StartApplication(app, scaleTo) => startApp(app, scaleTo, status)
          case ScaleApplication(app, scaleTo, toKill) => scaleApp(app, scaleTo, toKill, status)
          case RestartApplication(app) => restartApp(app, status)
          case StopApplication(app) => stopApp(app.copy(instances = 0))
          case ResolveArtifacts(app, urls) => resolveArtifacts(app, urls)
        }
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) => eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(_) => eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }

  def startApp(app: AppDefinition, scaleTo: Int, status: DeploymentStatus): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(
      AppStartActor.props(deploymentManager, status, driver, scheduler, launchQueue, taskTracker,
        eventBus, readinessCheckExecutor, app, scaleTo, promise)
    )
    promise.future
  }

  def scaleApp(app: AppDefinition, scaleTo: Int,
    toKill: Option[Iterable[Task]],
    status: DeploymentStatus): Future[Unit] = {
    val runningTasks = taskTracker.appTasksLaunchedSync(app.id)
    def killToMeetConstraints(notSentencedAndRunning: Iterable[Task], toKillCount: Int) =
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

  def stopApp(app: AppDefinition): Future[Unit] = {
    val tasks = taskTracker.appTasksLaunchedSync(app.id)
    // TODO: the launch queue is purged in stopApp, but it would make sense to do that before calling kill(tasks)
    killService.killTasks(tasks, TaskKillReason.DeletingApp).map(_ => ()).andThen {
      case Success(_) => scheduler.stopApp(app)
    }
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

  def resolveArtifacts(app: AppDefinition, urls: Map[URL, String]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[ResolveArtifactsActor], app, urls, promise, storage))
    promise.future.map(_ => ())
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
    taskTracker: TaskTracker,
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
