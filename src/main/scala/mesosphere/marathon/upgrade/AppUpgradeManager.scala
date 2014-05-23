package mesosphere.marathon.upgrade

import akka.actor._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.SchedulerDriver
import scala.concurrent.{Future, Promise}
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue}
import akka.event.EventStream
import scala.Some
import mesosphere.marathon.ConcurrentTaskUpgradeException

class AppUpgradeManager(
  taskTracker: TaskTracker,
  taskQueue: TaskQueue,
  eventBus: EventStream
) extends Actor with ActorLogging {
  import AppUpgradeManager._
  import context.dispatcher

  type AppID = String

  var runningUpgrades: Map[AppID, ActorRef] = Map.empty

  def receive = {
    case Upgrade(driver, app, keepAlive) if !runningUpgrades.contains(app.id) =>
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
          sender))
      runningUpgrades += app.id -> ref

    case _: Upgrade =>
      sender ! Status.Failure(new ConcurrentTaskUpgradeException("Upgrade is already in progress"))

    case Rollback(driver, app, keepAlive) =>
      val origSender = sender
      runningUpgrades.get(app.id) match {
        case Some(ref) =>
          runningUpgrades -= app.id
          stopActor(ref) onComplete {
            case _ => self.tell(Upgrade(driver, app, keepAlive), origSender)
          }

        case _ => self.tell(Upgrade(driver, app, keepAlive), origSender)
      }

    case UpgradeFinished(id) =>
      log.info(s"Removing $id from list of running upgrades")
      runningUpgrades -= id
  }

  def stopActor(ref: ActorRef): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise))
    promise.future
  }
}

object AppUpgradeManager {
  case class Upgrade(driver: SchedulerDriver, app: AppDefinition, keepAlive: Int)
  case class Rollback(driver: SchedulerDriver, app: AppDefinition, keepAlive: Int)
  case class UpgradeFinished(id: String)
}
