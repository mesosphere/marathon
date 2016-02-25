package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging, Cancellable }
import akka.event.EventStream
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.StoppingBehavior.{ KillNextBatch, KillAllTasks }
import org.apache.mesos.{ Protos, SchedulerDriver }

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.math.min

trait StoppingBehavior extends Actor with ActorLogging {
  import context.dispatcher

  def driver: SchedulerDriver
  def eventBus: EventStream
  def promise: Promise[Unit]
  def taskTracker: TaskTracker
  def appId: PathId
  var idsToKill: mutable.Set[Task.Id]
  var remainingIdsToKill: mutable.Queue[Task.Id] = mutable.Queue.empty
  var periodicalCheck: Cancellable = _

  final override def preStart(): Unit = {
    log.info(s"Killing ${idsToKill.size} instances")

    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    initKillAllTasks()
  }

  final override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The operation has been cancelled"))
  }

  val taskFinished = "^TASK_(ERROR|FAILED|FINISHED|LOST|KILLED)$".r

  def receive: Receive = {
    case MesosStatusUpdateEvent(_, taskId, taskFinished(_), _, _, _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill.remove(taskId)
      killNextTasks(1)
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case KillAllTasks =>
      val trackerIds = taskTracker.appTasksSync(appId).map(_.taskId).toSet
      idsToKill = idsToKill.filter(trackerIds)
      remainingIdsToKill = mutable.Queue(idsToKill.toSeq: _*)
      scheduleNextBatch()

    case KillNextBatch =>
      killNextTasks(100)
      scheduleNextBatch()

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  def killNextTasks(toKill: Int): Unit = {
    (0 until min(toKill, remainingIdsToKill.size)) foreach { _ =>
      driver.killTask(remainingIdsToKill.dequeue().mesosTaskId)
    }
  }

  def initKillAllTasks(): Unit = {
    remainingIdsToKill = mutable.Queue(idsToKill.toSeq: _*)
    scheduleNextBatch()
  }

  def checkFinished(): Unit = {
    if (idsToKill.isEmpty) {
      log.info("Successfully killed all the tasks")
      promise.success(())
      context.stop(self)
    }
  }

  def scheduleKillAllTasks(): Unit =
    periodicalCheck = context.system.scheduler.scheduleOnce(5.seconds, self, KillAllTasks)

  def scheduleNextBatch(): Unit = {
    if (remainingIdsToKill.nonEmpty) context.system.scheduler.scheduleOnce(2.seconds, self, KillNextBatch)
    else {
      log.info("Retry kill")
      checkFinished()
      scheduleKillAllTasks()
    }
  }
}

object StoppingBehavior {
  case object KillNextBatch
  case object KillAllTasks
}
