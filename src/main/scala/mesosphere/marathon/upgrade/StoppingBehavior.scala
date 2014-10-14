package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, Cancellable }
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.StoppingBehavior.SynchronizeTasks
import org.apache.mesos.{ Protos, SchedulerDriver }

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._

trait StoppingBehavior extends Actor with ActorLogging {
  import context.dispatcher

  def driver: SchedulerDriver
  def eventBus: EventStream
  def promise: Promise[Unit]
  def taskTracker: TaskTracker
  def appId: PathId
  var idsToKill: mutable.Set[String]
  var periodicalCheck: Cancellable = _

  def initializeStop(): Unit

  final override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    initializeStop()
    scheduleSynchronization()
    checkFinished()
  }

  final override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The operation has been cancelled"))
  }

  val taskFinished = "^TASK_(FAILED|FINISHED|LOST|KILLED)$".r

  def receive: Receive = {
    case MesosStatusUpdateEvent(_, taskId, taskFinished(_), _, _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill.remove(taskId)
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case SynchronizeTasks =>
      val trackerIds = taskTracker.get(appId).map(_.getId).toSet
      idsToKill = idsToKill.filter(trackerIds)

      idsToKill.foreach { id =>
        driver.killTask(
          Protos.TaskID
            .newBuilder
            .setValue(id)
            .build())
      }

      scheduleSynchronization()
      checkFinished()

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  def checkFinished(): Unit =
    if (idsToKill.isEmpty) {
      log.info("Successfully killed all the tasks")
      promise.success(())
      periodicalCheck.cancel()
      context.stop(self)
    }

  def scheduleSynchronization(): Unit =
    periodicalCheck = context.system.scheduler.scheduleOnce(5.seconds, self, SynchronizeTasks)
}

object StoppingBehavior {
  case object SynchronizeTasks
}
