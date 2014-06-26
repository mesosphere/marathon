package mesosphere.marathon.upgrade

import akka.actor._
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{ AppRepository, PathId }
import org.apache.mesos.SchedulerDriver
import scala.concurrent.{ Future, Promise }
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import akka.event.EventStream
import mesosphere.marathon.{ SchedulerActions, ConcurrentTaskUpgradeException }
import scala.collection.mutable

class AppUpgradeManager(
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    scheduler: SchedulerActions,
    eventBus: EventStream) extends Actor with ActorLogging {
  import AppUpgradeManager._
  import context.dispatcher

  val runningUpgrades: mutable.Map[PathId, ActorRef] = mutable.Map.empty
  val runningDeployments: mutable.Map[PathId, ActorRef] = mutable.Map.empty[PathId, ActorRef]

  def receive = {
    case Upgrade(driver, app, keepAlive, maxRunning) if !runningUpgrades.contains(app.id) =>
      val ref = context.actorOf(
        Props(
          classOf[AppUpgradeActor],
          self,
          driver,
          taskTracker,
          taskQueue,
          eventBus,
          app,
          keepAlive,
          maxRunning,
          sender))
      runningUpgrades += app.id -> ref

    case _: Upgrade =>
      sender ! Status.Failure(new ConcurrentTaskUpgradeException("Upgrade is already in progress"))

    case CancelUpgrade(appId, reason) =>
      val origSender = sender
      runningUpgrades.remove(appId) match {
        case Some(ref) =>
          stopActor(ref, reason) onComplete {
            case _ => origSender ! UpgradeCanceled(appId)
          }

        case _ => origSender ! UpgradeCanceled(appId)
      }

    case CancelDeployment(id, reason) =>
      val origSender = sender
      runningDeployments.remove(id) match {
        case Some(ref) =>
          stopActor(ref, reason) onComplete {
            case _ => origSender ! DeploymentCanceled(id)
          }

        case _ => origSender ! DeploymentCanceled(id)
      }

    case UpgradeFinished(id) =>
      log.info(s"Removing $id from list of running upgrades")
      runningUpgrades -= id

    case DeploymentFinished(id) =>
      log.info(s"Removing $id from list of running deployments")
      runningDeployments -= id

    case PerformDeployment(driver, plan) if !runningDeployments.contains(plan.target.id) =>
      val ref = context.actorOf(Props(classOf[DeploymentActor], self, sender, appRepository, driver, scheduler, plan, taskTracker, taskQueue, eventBus))
      runningDeployments += plan.target.id -> ref
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }
}

object AppUpgradeManager {
  case class Upgrade(driver: SchedulerDriver, app: AppDefinition, keepAlive: Int, maxRunning: Option[Int] = None)
  case class PerformDeployment(driver: SchedulerDriver, plan: DeploymentPlan)
  case class CancelUpgrade(appId: PathId, reason: Throwable)
  case class CancelDeployment(id: PathId, reason: Throwable)

  case class UpgradeFinished(appId: PathId)
  case class UpgradeCanceled(appId: PathId)
  case class DeploymentFinished(appId: PathId)
  case class DeploymentCanceled(id: PathId)
}
