package mesosphere.marathon.upgrade

import akka.actor._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.SchedulerDriver
import scala.concurrent.{Future, Promise}
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue}
import akka.event.EventStream
import scala.Some
import akka.actor.SupervisorStrategy.Stop
import akka.pattern.pipe
import mesosphere.marathon.ConcurrentTaskUpgradeException
import scala.collection.immutable
import mesosphere.marathon.event.{HealthStatusChanged, MesosStatusUpdateEvent}

class AppUpgradeManager(
  taskTracker: TaskTracker,
  taskQueue: TaskQueue,
  eventBus: EventStream
) extends Actor {
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

class AppUpgradeActor(
  manager: ActorRef,
  driver: SchedulerDriver,
  taskTracker: TaskTracker,
  taskQueue: TaskQueue,
  eventBus: EventStream,
  app: AppDefinition,
  keepAlive: Int,
  receiver: ActorRef
) extends Actor with ActorLogging {
  import context.dispatcher
  import AppUpgradeManager._

  val replacePromise = Promise[Boolean]()
  val startPromise = Promise[Boolean]()
  val stopPromise = Promise[Boolean]()
  val oldInstances = taskTracker.get(app.id).to[immutable.Set]

  var starter: Option[ActorRef] = None
  var stopper: Option[ActorRef] = None
  var replacer: Option[ActorRef] = None

  override def preStart(): Unit = {
    startReplacer()
    startStopper()
    startStarter()

    val res = for {
      stopped <- stopPromise.future
      started <- startPromise.future
      replaced <- replacePromise.future
    } yield stopped && started && replaced

    res andThen { case _ =>
      manager ! UpgradeFinished(app.id)
      log.info(s"Finished upgrade of ${app.id}")
      context.stop(self)
    } pipeTo receiver
  }

  // If one of the tasks fails we have to fail all of them
  override def supervisorStrategy: SupervisorStrategy = AllForOneStrategy(maxNrOfRetries = 0) {
    case t: Throwable => Stop
  }

  // We only supervise the other tasks, so no need to process messages
  def receive = PartialFunction.empty

  private def startReplacer(): Unit = {
    if (keepAlive > 0) {
      val ref = context.actorOf(
        Props(
          classOf[TaskReplaceActor],
          driver,
          eventBus,
          app.id,
          app.version.toString,
          app.instances,
          oldInstances.drop(app.instances - keepAlive),
          replacePromise))
      replacer = Some(ref)
    } else {
      replacePromise.success(true)
    }
  }

  private def startStopper(): Unit = {
    if (oldInstances.size > keepAlive) {
      stopper = Some(context.actorOf(
        Props(
          classOf[TaskKillActor],
          driver,
          eventBus,
          oldInstances.take(oldInstances.size - keepAlive),
          stopPromise)))
    } else {
      stopPromise.success(true)
    }
  }

  private def startStarter(): Unit = {
    if (app.instances > 0) {
      starter = Some(context.actorOf(
        Props(
          classOf[TaskStartActor],
          taskQueue,
          eventBus,
          app,
          startPromise)))
    } else {
      startPromise.success(true)
    }
  }
}
