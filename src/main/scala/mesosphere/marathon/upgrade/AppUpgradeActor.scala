package mesosphere.marathon.upgrade

import akka.actor._
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor.SupervisorStrategy.Stop
import akka.pattern.pipe

class AppUpgradeActor(
    manager: ActorRef,
    driver: SchedulerDriver,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    eventBus: EventStream,
    app: AppDefinition,
    keepAlive: Int,
    maxRunning: Option[Int],
    receiver: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher
  import AppUpgradeManager._
  import AppUpgradeActor._

  val replacePromise = Promise[Boolean]()
  val startPromise = Promise[Boolean]()
  val stopPromise = Promise[Boolean]()
  val resultPromise = Promise[Boolean]()
  // sort by startedAt to kill newer instances first
  val oldInstances = taskTracker.get(app.id).toList.sortWith(_.getStartedAt > _.getStartedAt)
  val nrToStartImmediately: Int =
    maxRunning.fold(app.instances) { x =>
      math.min(app.instances, x - keepAlive)
    }

  override def preStart(): Unit = {
    startReplacer()
    startStopper()
    startStarter()

    val res = for {
      stopped <- stopPromise.future
      started <- startPromise.future
      replaced <- replacePromise.future
    } yield stopped && started && replaced

    res andThen {
      case x =>
        manager ! UpgradeFinished(app.id)
        log.info(s"Finished upgrade of ${app.id}")
        context.stop(self)
        resultPromise.tryComplete(x)
    }

    resultPromise.future pipeTo receiver
  }

  // If one of the tasks fails we have to fail all of them
  override def supervisorStrategy: SupervisorStrategy = AllForOneStrategy(maxNrOfRetries = 0) {
    case t: Throwable => Stop
  }

  def receive = {
    case Cancel(reason) =>
      resultPromise.failure(reason)
      context.stop(self)
  }

  private def startReplacer(): Unit = {
    if (keepAlive > 0) {
      context.actorOf(
        Props(
          classOf[TaskReplaceActor],
          driver,
          taskQueue,
          eventBus,
          app,
          nrToStartImmediately,
          oldInstances.drop(app.instances - keepAlive).toSet,
          replacePromise), "Replacer")
    }
    else {
      replacePromise.success(true)
    }
  }

  private def startStopper(): Unit = {
    if (oldInstances.size > keepAlive) {
      context.actorOf(
        Props(
          classOf[TaskKillActor],
          driver,
          eventBus,
          oldInstances.take(oldInstances.size - keepAlive).toSet,
          stopPromise), "Stopper")
    }
    else {
      stopPromise.success(true)
    }
  }

  private def startStarter(): Unit = {
    if (app.instances > 0) {
      context.actorOf(
        Props(
          classOf[TaskStartActor],
          taskQueue,
          eventBus,
          app,
          nrToStartImmediately,
          startPromise), "Starter")
    }
    else {
      startPromise.success(true)
    }
  }
}

object AppUpgradeActor {
  case class Cancel(reason: Throwable)
}
