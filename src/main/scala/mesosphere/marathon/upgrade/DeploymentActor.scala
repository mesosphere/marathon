package mesosphere.marathon.upgrade

import java.net.URL
import mesosphere.marathon.event.{ DeploymentStatus, DeploymentStepSuccess, DeploymentStepFailure }

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
import mesosphere.marathon.upgrade.DeploymentManager.{ DeploymentStepInfo, DeploymentFinished }

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
    // it doesn't matter if it's a failure or success,
    // it just has to be removed from the running deployments
    parent ! DeploymentFinished(plan.id)
  }

  def receive: Receive = {
    case NextStep if steps.hasNext =>
      val step = steps.next()
      currentStepNr += 1
      currentStep = Some(step)
      parent ! DeploymentStepInfo(plan, currentStep.getOrElse(DeploymentStep(Nil)), currentStepNr)

      performStep(step) onComplete {
        case Success(_) =>
          self ! NextStep
        case Failure(t) =>
          self ! Cancel(t)
      }

    case NextStep =>
      // no more steps, we're done
      receiver ! Finished
      context.stop(self)

    case Cancel(t) =>
      receiver ! Status.Failure(t)
      context.stop(self)

  }

  def performStep(step: DeploymentStep): Future[Unit] = {
    if (step.actions.isEmpty) {
      Future.successful(())
    }
    else {
      eventBus.publish(DeploymentStatus(plan, step))

      val futures = step.actions.map {
        case StartApplication(app, scaleTo) => startApp(app, scaleTo)
        case ScaleApplication(app, scaleTo) => scaleApp(app, scaleTo)
        case StopApplication(app)           => stopApp(app)
        case KillAllOldTasksOf(app) =>
          val runningTasks = taskTracker.get(app.id).toSeq
          killTasks(app.id, runningTasks.filterNot(_.getVersion == app.version.toString))
        case RestartApplication(app, scaleOldTo, scaleNewTo) => restartApp(app, scaleOldTo, scaleNewTo)
        case ResolveArtifacts(app, urls)                     => resolveArtifacts(app, urls)
      }

      Future.sequence(futures).map(_ => ()) andThen {
        case Success(_) => eventBus.publish(DeploymentStepSuccess(plan, step))
        case Failure(_) => eventBus.publish(DeploymentStepFailure(plan, step))
      }
    }
  }

  def startApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    healthCheckManager.addAllFor(app) // ensure health check actors are in place before tasks are launched
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
    storeAndThen(app, promise.future)
  }

  def scaleApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val runningTasks = taskTracker.get(app.id)
    val res = if (scaleTo == runningTasks.size) {
      Future.successful(())
    }
    else if (scaleTo > runningTasks.size) {
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
          scaleTo - runningTasks.size,
          app.healthChecks.nonEmpty,
          promise
        )
      )
      promise.future.map(_ => ())
    }
    else {
      killTasks(app.id, runningTasks.toSeq.sortBy(_.getStartedAt).drop(scaleTo))
    }

    storeAndThen(app, res)
  }

  def killTasks(appId: PathId, tasks: Seq[MarathonTask]): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[TaskKillActor], driver, appId, taskTracker, eventBus, tasks.toSet, promise))
    promise.future.map(_ => ())
  }

  def stopApp(app: AppDefinition): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[AppStopActor], driver, scheduler, taskTracker, eventBus, app, promise))
    promise.future.andThen {
      case Success(_) => appRepository.expunge(app.id)
    }
  }

  def restartApp(app: AppDefinition, scaleOldTo: Int, scaleNewTo: Int): Future[Unit] = {
    healthCheckManager.addAllFor(app) // ensure health check actors are in place before tasks are launched
    val startPromise = Promise[Unit]()
    val stopPromise = Promise[Unit]()
    val runningTasks = taskTracker.get(app.id).toSeq.sortBy(_.getStartedAt)
    val tasksToKill = runningTasks.filterNot(_.getVersion == app.version.toString).drop(scaleOldTo)
    val runningNew = runningTasks.filter(_.getVersion == app.version.toString)
    val nrToStart = scaleNewTo - runningNew.size

    context.actorOf(
      Props(
        classOf[TaskStartActor],
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        eventBus,
        app,
        nrToStart,
        app.healthChecks.nonEmpty,
        startPromise
      )
    )

    context.actorOf(
      Props(
        classOf[TaskKillActor],
        driver,
        app.id,
        taskTracker,
        eventBus,
        tasksToKill.toSet,
        stopPromise
      )
    )

    val res = startPromise.future.zip(stopPromise.future).map(_ => ())
    storeAndThen(app, res)
  }

  def resolveArtifacts(app: AppDefinition, urls: Map[URL, String]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[ResolveArtifactsActor], app, urls, promise, storage))
    promise.future.map(_ => ())
  }

  def storeAndThen[A](app: AppDefinition, future: Future[A]): Future[A] = for {
    _ <- appRepository.store(app)
    x <- future
  } yield x

}

object DeploymentActor {
  case object NextStep
  case object Finished
  final case class Cancel(reason: Throwable)
}
