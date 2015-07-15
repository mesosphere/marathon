package mesosphere.marathon.upgrade

import java.net.URL
import mesosphere.marathon.event.{ DeploymentStatus, DeploymentStepSuccess, DeploymentStepFailure }
import mesosphere.mesos.Constraints

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

import akka.actor._
import akka.event.EventStream
import org.apache.mesos.SchedulerDriver

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ PathId, AppDefinition, AppRepository }
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentFailed, DeploymentStepInfo, DeploymentFinished }

class DeploymentActor(
    parent: ActorRef,
    receiver: ActorRef,
    appRepository: AppRepository,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentActor._

  val steps = plan.steps.iterator
  var currentStep: Option[DeploymentStep] = None
  var currentStepNr: Int = 0

  override def preStart(): Unit = {
    self ! NextStep
  }

  override def postStop(): Unit = {
    parent ! DeploymentFinished(plan)
  }

  def receive: Receive = {
    case NextStep if steps.hasNext =>
      val step = steps.next()
      currentStepNr += 1
      currentStep = Some(step)
      parent ! DeploymentStepInfo(plan, currentStep.getOrElse(DeploymentStep(Nil)), currentStepNr)

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
      receiver ! DeploymentFailed(plan, t)
      context.stop(self)
  }

  def performStep(step: DeploymentStep): Future[Unit] = {
    if (step.actions.isEmpty) {
      Future.successful(())
    }
    else {
      eventBus.publish(DeploymentStatus(plan, step))

      val futures = step.actions.map { action =>
        healthCheckManager.addAllFor(action.app) // ensure health check actors are in place before tasks are launched
        action match {
          case StartApplication(app, scaleTo)         => storeAndThen(app) { startApp(app, scaleTo) }
          case ScaleApplication(app, scaleTo, toKill) => storeAndThen(app) { scaleApp(app, scaleTo, toKill) }
          case RestartApplication(app)                => storeAndThen(app) { restartApp(app) }
          case StopApplication(app)                   => storeAndThen(app.copy(instances = 0)) { stopApp(app) }
          case ResolveArtifacts(app, urls)            => resolveArtifacts(app, urls)
        }
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) => eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(_) => eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }

  def startApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(
      Props(
        classOf[AppStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        eventBus,
        app,
        scaleTo,
        promise
      )
    )
    promise.future
  }

  def scaleApp(app: AppDefinition, scaleTo: Int, toKill: Option[Set[MarathonTask]]): Future[Unit] = {
    val runningTasks = taskTracker.get(app.id)
    def killToMeetConstraints(notSentencedAndRunning: Set[MarathonTask], toKillCount: Int) =
      Constraints.selectTasksToKill(app, notSentencedAndRunning, toKillCount)

    val ScalingProposition(tasksToKill, tasksToStart) = ScalingProposition.propose(
      runningTasks, toKill, killToMeetConstraints, scaleTo)

    def killTasksIfNeeded: Future[Unit] = tasksToKill.fold(Future.successful(())) {
      killTasks(app.id, _)
    }

    def startTasksIfNeeded: Future[Unit] = tasksToStart.fold(Future.successful(())) { _ =>
      val promise = Promise[Unit]()
      context.actorOf(
        Props(
          classOf[TaskStartActor],
          driver,
          scheduler,
          taskQueue,
          taskTracker,
          eventBus,
          app,
          scaleTo,
          promise
        )
      )
      promise.future
    }

    killTasksIfNeeded.flatMap(_ => startTasksIfNeeded)
  }

  def killTasks(appId: PathId, tasks: Seq[MarathonTask]): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[TaskKillActor], driver, appId, taskTracker, eventBus, tasks.toSet, promise))
    promise.future
  }

  def stopApp(app: AppDefinition): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[AppStopActor], driver, taskTracker, eventBus, app, promise))
    promise.future.andThen {
      case Success(_) => scheduler.stopApp(driver, app)
    }
  }

  def restartApp(app: AppDefinition): Future[Unit] = {
    if (app.instances == 0) {
      Future.successful(())
    }
    else {
      val promise = Promise[Unit]()
      context.actorOf(
        Props(
          new TaskReplaceActor(
            driver,
            taskQueue,
            taskTracker,
            eventBus,
            app,
            promise)))
      promise.future
    }
  }

  def resolveArtifacts(app: AppDefinition, urls: Map[URL, String]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[ResolveArtifactsActor], app, urls, promise, storage))
    promise.future.map(_ => ())
  }

  def storeAndThen[A](app: AppDefinition)(cont: => Future[A]): Future[A] = {
    appRepository.store(app) flatMap { _ => cont }
  }
}

object DeploymentActor {
  case object NextStep
  case object Finished
  final case class Cancel(reason: Throwable)
  final case class Fail(reason: Throwable)
}
