package mesosphere.marathon.upgrade

import java.net.URL

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.event.{ DeploymentStatus, DeploymentStepFailure, DeploymentStepSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ AppDefinition, RunSpec }
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFailed, DeploymentFinished, DeploymentStepInfo }
import mesosphere.mesos.Constraints
import org.apache.mesos.SchedulerDriver

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

private class DeploymentActor(
    deploymentManager: ActorRef,
    receiver: ActorRef,
    driver: SchedulerDriver,
    killService: KillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor) extends Actor with ActorLogging {

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

  // scalastyle:off
  def performStep(step: DeploymentStep): Future[Unit] = {
    if (step.actions.isEmpty) {
      Future.successful(())
    } else {
      val status = DeploymentStatus(plan, step)
      eventBus.publish(status)

      val futures = step.actions.map { action =>
        action.runSpec match {
          case app: AppDefinition => healthCheckManager.addAllFor(app, Iterable.empty)
          case pod: PodDefinition => //ignore: no marathon based health check for pods
        }
        action match {
          case StartApplication(run, scaleTo) => startRunnable(run, scaleTo, status)
          case ScaleApplication(run, scaleTo, toKill) => scaleRunnable(run, scaleTo, toKill, status)
          case RestartApplication(run) => restartRunnable(run, status)
          case StopApplication(run) => stopRunnable(run.withInstances(0))
          case ResolveArtifacts(run, urls) => resolveArtifacts(urls)
        }
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) => eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(_) => eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }
  // scalastyle:on

  def startRunnable(runnableSpec: RunSpec, scaleTo: Int, status: DeploymentStatus): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(
      AppStartActor.props(deploymentManager, status, driver, scheduler, launchQueue, instanceTracker,
        eventBus, readinessCheckExecutor, runnableSpec, scaleTo, promise)
    )
    promise.future
  }

  def scaleRunnable(runnableSpec: RunSpec, scaleTo: Int,
    toKill: Option[Iterable[Instance]],
    status: DeploymentStatus): Future[Unit] = {
    val runningInstances = instanceTracker.specInstancesLaunchedSync(runnableSpec.id)
    def killToMeetConstraints(notSentencedAndRunning: Iterable[Instance], toKillCount: Int) = {
      Constraints.selectInstancesToKill(runnableSpec, notSentencedAndRunning, toKillCount)
    }

    val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
      runningInstances, toKill, killToMeetConstraints, scaleTo)

    def killTasksIfNeeded: Future[Unit] = tasksToKill.fold(Future.successful(())) { tasks =>
      killService.killInstances(tasks, KillReason.ScalingApp).map(_ => ())
    }

    def startTasksIfNeeded: Future[Unit] = tasksToStart.fold(Future.successful(())) { _ =>
      val promise = Promise[Unit]()
      context.actorOf(
        TaskStartActor.props(deploymentManager, status, driver, scheduler, launchQueue, instanceTracker, eventBus,
          readinessCheckExecutor, runnableSpec, scaleTo, promise)
      )
      promise.future
    }

    killTasksIfNeeded.flatMap(_ => startTasksIfNeeded)
  }

  def stopRunnable(runnableSpec: RunSpec): Future[Unit] = {
    val tasks = instanceTracker.specInstancesLaunchedSync(runnableSpec.id)
    // TODO: the launch queue is purged in stopRunnable, but it would make sense to do that before calling kill(tasks)
    killService.killInstances(tasks, KillReason.DeletingApp).map(_ => ()).andThen {
      case Success(_) => scheduler.stopRunSpec(runnableSpec)
    }
  }

  def restartRunnable(run: RunSpec, status: DeploymentStatus): Future[Unit] = {
    if (run.instances == 0) {
      Future.successful(())
    } else {
      val promise = Promise[Unit]()
      context.actorOf(TaskReplaceActor.props(deploymentManager, status, driver, killService,
        launchQueue, instanceTracker, eventBus, readinessCheckExecutor, run, promise))
      promise.future
    }
  }

  def resolveArtifacts(urls: Map[URL, String]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(ResolveArtifactsActor.props(urls, promise, storage))
    promise.future.map(_ => ())
  }
}

object DeploymentActor {
  case object NextStep
  case object Finished
  case class Cancel(reason: Throwable)
  case class Fail(reason: Throwable)
  case class DeploymentActionInfo(plan: DeploymentPlan, step: DeploymentStep, action: DeploymentAction)

  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    receiver: ActorRef,
    driver: SchedulerDriver,
    killService: KillService,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor): Props = {

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
      readinessCheckExecutor
    ))
  }
}
