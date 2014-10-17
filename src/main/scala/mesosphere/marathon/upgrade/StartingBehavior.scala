package mesosphere.marathon.upgrade

import akka.actor.{ ActorLogging, Actor }
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.event.{ MarathonHealthCheckEvent, MesosStatusUpdateEvent, HealthStatusChanged }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue }
import org.apache.mesos.Protos.TaskState
import org.apache.mesos.SchedulerDriver

import scala.concurrent.duration._

trait StartingBehavior { this: Actor with ActorLogging =>
  import StartingBehavior._
  import context.dispatcher

  def eventBus: EventStream
  def expectedSize: Int
  def withHealthChecks: Boolean
  def taskQueue: TaskQueue
  def driver: SchedulerDriver
  def scheduler: SchedulerActions
  def taskTracker: TaskTracker

  val app: AppDefinition
  val Version = app.version.toString
  var healthyTasks = Set.empty[String]
  var runningTasks = Set.empty[String]
  val AppId = app.id

  def initializeStart(): Unit

  final override def preStart(): Unit = {
    if (withHealthChecks) {
      eventBus.subscribe(self, classOf[MarathonHealthCheckEvent])
    }
    else {
      eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    }

    initializeStart()
    checkFinished()

    context.system.scheduler.scheduleOnce(5.seconds, self, Sync)
  }

  final def receive: PartialFunction[Any, Unit] = {
    val behavior =
      if (withHealthChecks) checkForHealthy
      else checkForRunning
    behavior orElse commonBehavior
  }

  final def checkForHealthy: Receive = {
    case HealthStatusChanged(AppId, taskId, Version, true, _, _) if !healthyTasks(taskId) =>
      healthyTasks += taskId
      log.info(s"$taskId is now healthy")
      checkFinished()
  }

  final def checkForRunning: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", _, app.`id`, _, _, Version, _, _) if !runningTasks(taskId) =>
      runningTasks += taskId
      log.info(s"Started $taskId")
      checkFinished()
  }

  def commonBehavior: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_FAILED" | "TASK_LOST" | "TASK_KILLED", _, app.`id`, _, _, Version, _, _) => // scalastyle:off line.size.limit
      log.warning(s"Failed to start $taskId for app ${app.id}. Rescheduling.")
      runningTasks -= taskId
      taskQueue.add(app)

    case Sync =>
      val actualSize = taskQueue.count(app) + taskTracker.count(app.id)

      if (actualSize < expectedSize) {
        for (_ <- 0 until (expectedSize - actualSize)) taskQueue.add(app)
      }
      context.system.scheduler.scheduleOnce(5.seconds, self, Sync)
  }

  def checkFinished(): Unit = {
    if (withHealthChecks && healthyTasks.size == expectedSize) {
      success()
    }
    else if (runningTasks.size == expectedSize) {
      success()
    }
  }

  def success(): Unit
}

object StartingBehavior {
  case object Sync
}
