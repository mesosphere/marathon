package mesosphere.marathon.upgrade

import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentFinished
import org.apache.mesos.SchedulerDriver

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

class DeploymentActor(
    parent: ActorRef,
    receiver: ActorRef,
    appRepository: AppRepository,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentActor._

  val steps = plan.steps.iterator

  override def preStart(): Unit = {
    self ! NextStep
  }

  override def postStop(): Unit = {
    // it doesn't matter if it's a failure or success,
    // it just has to be removed from the running deployments
    parent ! DeploymentFinished(plan.id)
  }

  def receive = {
    case NextStep if steps.hasNext =>
      performStep(steps.next()) onComplete {
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
      val futures = step.actions.map {
        case StartApplication(app, scaleTo) => startApp(app, scaleTo)
        case ScaleApplication(app, scaleTo) => scaleApp(app, scaleTo)
        case StopApplication(app)           => stopApp(app)
        case KillAllOldTasksOf(app) =>
          val runningTasks = taskTracker.get(app.id).toSeq
          killTasks(runningTasks.filterNot(_.getVersion == app.version.toString))

        case RestartApplication(app, scaleOldTo, scaleNewTo) => restartApp(app, scaleOldTo, scaleNewTo)
      }

      Future.sequence(futures).map(_ => ())
    }
  }

  def startApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[AppStartActor], driver, scheduler, taskQueue, eventBus, app, scaleTo, promise))
    storeOnSuccess(app, promise.future)
  }

  def scaleApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val runningTasks = taskTracker.get(app.id)
    val res = if (scaleTo == runningTasks.size) {
      Future.successful(())
    }
    else if (scaleTo > runningTasks.size) {
      val promise = Promise[Boolean]()
      context.actorOf(Props(classOf[TaskStartActor], taskQueue, eventBus, app, scaleTo - runningTasks.size, app.healthChecks.nonEmpty, promise))
      promise.future.map(_ => ())
    }
    else {
      killTasks(runningTasks.toSeq.sortBy(_.getStartedAt).drop(scaleTo))
    }

    storeOnSuccess(app, res)
  }

  def killTasks(tasks: Seq[MarathonTask]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[TaskKillActor], driver, eventBus, tasks.toSet, promise))
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
    val startPromise = Promise[Boolean]()
    val stopPromise = Promise[Boolean]()
    val runningTasks = taskTracker.get(app.id).toSeq.sortBy(_.getStartedAt)
    val tasksToKill = runningTasks.filterNot(_.getVersion == app.version.toString).drop(scaleOldTo)
    val runningNew = runningTasks.filter(_.getVersion == app.version.toString)
    val nrToStart = scaleNewTo - runningNew.size

    context.actorOf(Props(classOf[TaskStartActor], taskQueue, eventBus, app, nrToStart, app.healthChecks.nonEmpty, startPromise))

    context.actorOf(Props(classOf[TaskKillActor], driver, eventBus, tasksToKill.toSet, stopPromise))

    val res = startPromise.future.zip(stopPromise.future).map(_ => ())
    storeOnSuccess(app, res)
  }

  def storeOnSuccess[A](app: AppDefinition, future: Future[A]): Future[A] = for {
    x <- future
    _ <- appRepository.store(app)
  } yield x
}

object DeploymentActor {
  case object NextStep
  case object Finished
  case class Cancel(reason: Throwable)
}
