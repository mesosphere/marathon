package mesosphere.marathon.upgrade

import akka.actor.{ Actor, ActorLogging }
import akka.event.EventStream
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ HealthStatusChanged, MarathonHealthCheckEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.SchedulerDriver

import scala.concurrent.duration._

trait StartingBehavior { this: Actor with ActorLogging =>
  import context.dispatcher
  import mesosphere.marathon.upgrade.StartingBehavior._

  def eventBus: EventStream
  def scaleTo: Int
  def nrToStart: Int
  def taskQueue: LaunchQueue
  def driver: SchedulerDriver
  def scheduler: SchedulerActions
  def taskTracker: TaskTracker

  val app: AppDefinition
  val Version = app.version
  // FIXME: Don't use a string here!
  val VersionString = app.version.toString
  var atLeastOnceHealthyTasks = Set.empty[String]
  var startedRunningTasks = Set.empty[String]
  val AppId = app.id
  val withHealthChecks: Boolean = app.healthChecks.nonEmpty

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

  final override def receive: Receive = {
    val behavior =
      if (withHealthChecks) checkForHealthy
      else checkForRunning
    behavior orElse commonBehavior: PartialFunction[Any, Unit] // type annotation makes Intellij happy
  }

  final def checkForHealthy: Receive = {
    case HealthStatusChanged(AppId, taskId, Version, true, _, _) if !atLeastOnceHealthyTasks(taskId.idString) =>
      atLeastOnceHealthyTasks += taskId.idString
      log.info(s"$taskId is now healthy")
      checkFinished()
  }

  final def checkForRunning: Receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_RUNNING", _, app.`id`, _, _, _, VersionString, _, _) if !startedRunningTasks(taskId.idString) => // scalastyle:off line.size.limit
      startedRunningTasks += taskId.idString
      log.info(s"New task $taskId now running during app ${app.id.toString} scaling, " +
        s"${nrToStart - startedRunningTasks.size} more to go")
      checkFinished()
  }

  def commonBehavior: Receive = {
    case MesosStatusUpdateEvent(_, taskId, StartErrorState(_), _, app.`id`, _, _, _, VersionString, _, _) => // scalastyle:off line.size.limit
      log.warning(s"New task [$taskId] failed during app ${app.id.toString} scaling, queueing another task")
      startedRunningTasks -= taskId.idString
      taskQueue.add(app)

    case Sync =>
      val actualSize = taskQueue.get(app.id).map(_.finalTaskCount).getOrElse(taskTracker.countLaunchedAppTasksSync(app.id))
      val tasksToStartNow = Math.max(scaleTo - actualSize, 0)
      if (tasksToStartNow > 0) {
        log.info(s"Reconciling tasks during app ${app.id.toString} scaling: queuing $tasksToStartNow new tasks")
        taskQueue.add(app, tasksToStartNow)
      }
      context.system.scheduler.scheduleOnce(5.seconds, self, Sync)
  }

  def checkFinished(): Unit = {
    val started =
      if (withHealthChecks) atLeastOnceHealthyTasks.size
      else startedRunningTasks.size
    if (started == nrToStart) {
      success()
    }
  }

  def success(): Unit
}

object StartingBehavior {
  case object Sync
}

private object StartErrorState {
  def unapply(state: String): Option[String] = state match {
    case "TASK_ERROR" | "TASK_FAILED" | "TASK_KILLED" | "TASK_LOST" => Some(state)
    case _ => None
  }
}
